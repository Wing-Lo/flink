/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.streamstatus;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.io.PushingAsyncDataInput.DataOutput;
import org.apache.flink.util.Preconditions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A {@code StatusWatermarkValve} embodies the logic of how {@link Watermark} and {@link StreamStatus} are propagated to
 * downstream outputs, given a set of one or multiple input channels that continuously receive them. Usages of this
 * class need to define the number of input channels that the valve needs to handle, as well as provide a implementation of
 * {@link DataOutput}, which is called by the valve only when it determines a new watermark or stream status can be propagated.
 */
@Internal
public class StatusWatermarkValve {

	private final DataOutput output;

	// ------------------------------------------------------------------------
	//	Runtime state for watermark & stream status output determination
	// ------------------------------------------------------------------------

	/**
	 * Array of current status of all input channels. Changes as watermarks & stream statuses are
	 * fed into the valve.
	 */
	private final InputChannelStatus[] channelStatuses;

	/** The last watermark emitted from the valve. */
	private long lastOutputWatermark;
	private Map<String, Watermark> lastOutputWatermarkMap;

	/** The last stream status emitted from the valve. */
	private StreamStatus lastOutputStreamStatus;

	/**
	 * Returns a new {@code StatusWatermarkValve}.
	 *
	 * @param numInputChannels the number of input channels that this valve will need to handle
	 * @param output the customized output handler for the valve
	 */
	public StatusWatermarkValve(int numInputChannels, DataOutput output) {
		checkArgument(numInputChannels > 0);
		this.channelStatuses = new InputChannelStatus[numInputChannels];
		for (int i = 0; i < numInputChannels; i++) {
			channelStatuses[i] = new InputChannelStatus();
			channelStatuses[i].watermark = Long.MIN_VALUE;
			channelStatuses[i].streamStatus = StreamStatus.ACTIVE;
			channelStatuses[i].isWatermarkAligned = true;
		}

		this.output = checkNotNull(output);

		this.lastOutputWatermarkMap = new ConcurrentHashMap<>();
		this.lastOutputWatermark = Long.MIN_VALUE;
		this.lastOutputStreamStatus = StreamStatus.ACTIVE;
	}

	private long getLastOutputWatermark(){
		return Long.MIN_VALUE;
	}

	private long getLastOutputWatermark(Watermark watermark){
		if(watermark == null){
			return getLastOutputWatermark();
		}
		if(null == watermark.getKey() || "".equals(watermark.getKey())){
			return getLastOutputWatermark();
		}
		Watermark watermark1 = lastOutputWatermarkMap.get(watermark.getKey());
		if(watermark1 == null){
			return getLastOutputWatermark();
		}
		return watermark1.getTimestamp();
	}

	private void setLastOutputWatermark(long watermark){
		this.lastOutputWatermark = watermark;
	}

	private void setLastOutputWatermark(Watermark watermark){
		if(watermark == null || null == watermark.getKey() || "".equals(watermark.getKey())){
			return;
		}
		lastOutputWatermarkMap.put(watermark.getKey(), watermark);
	}

	/**
	 * Feed a {@link Watermark} into the valve. If the input triggers the valve to output a new Watermark,
	 * {@link DataOutput#emitWatermark(Watermark)} will be called to process the new Watermark.
	 *
	 * @param watermark the watermark to feed to the valve
	 * @param channelIndex the index of the channel that the fed watermark belongs to (index starting from 0)
	 */
	public void inputWatermark(Watermark watermark, int channelIndex) throws Exception {
		// ignore the input watermark if its input channel, or all input channels are idle (i.e. overall the valve is idle).
		if (lastOutputStreamStatus.isActive() && channelStatuses[channelIndex].streamStatus.isActive()) {
			long watermarkMillis = watermark.getTimestamp();

			// if the input watermark's value is less than the last received watermark for its input channel, ignore it also.
			if (watermarkMillis > channelStatuses[channelIndex].getWatermarkTimestampByWatermark(watermark)) {
				channelStatuses[channelIndex].setWatermark(watermark);

				// previously unaligned input channels are now aligned if its watermark has caught up
				if (!channelStatuses[channelIndex].isWatermarkAligned && watermarkMillis >= getLastOutputWatermark(watermark)) {
					channelStatuses[channelIndex].isWatermarkAligned = true;
				}

				// now, attempt to find a new min watermark across all aligned channels
				findAndOutputNewMinWatermarkAcrossAlignedChannels(watermark);
			}
		}
	}

	/**
	 * Feed a {@link StreamStatus} into the valve. This may trigger the valve to output either a new Stream Status,
	 * for which {@link DataOutput#emitStreamStatus(StreamStatus)} will be called, or a new Watermark,
	 * for which {@link DataOutput#emitWatermark(Watermark)} will be called.
	 *
	 * @param streamStatus the stream status to feed to the valve
	 * @param channelIndex the index of the channel that the fed stream status belongs to (index starting from 0)
	 */
	public void inputStreamStatus(StreamStatus streamStatus, int channelIndex) throws Exception {
		// only account for stream status inputs that will result in a status change for the input channel
		if (streamStatus.isIdle() && channelStatuses[channelIndex].streamStatus.isActive()) {
			// handle active -> idle toggle for the input channel
			channelStatuses[channelIndex].streamStatus = StreamStatus.IDLE;

			// the channel is now idle, therefore not aligned
			channelStatuses[channelIndex].isWatermarkAligned = false;

			// if all input channels of the valve are now idle, we need to output an idle stream
			// status from the valve (this also marks the valve as idle)
			if (!InputChannelStatus.hasActiveChannels(channelStatuses)) {

				// now that all input channels are idle and no channels will continue to advance its watermark,
				// we should "flush" all watermarks across all channels; effectively, this means emitting
				// the max watermark across all channels as the new watermark. Also, since we already try to advance
				// the min watermark as channels individually become IDLE, here we only need to perform the flush
				// if the watermark of the last active channel that just became idle is the current min watermark.
				if (channelStatuses[channelIndex].getWatermark() == getLastOutputWatermark()) {
					findAndOutputMaxWatermarkAcrossAllChannels();
				}

				lastOutputStreamStatus = StreamStatus.IDLE;
				output.emitStreamStatus(lastOutputStreamStatus);
			} else if (channelStatuses[channelIndex].getWatermark() == getLastOutputWatermark()) {
				// if the watermark of the channel that just became idle equals the last output
				// watermark (the previous overall min watermark), we may be able to find a new
				// min watermark from the remaining aligned channels
				findAndOutputNewMinWatermarkAcrossAlignedChannels(null);
			}
		} else if (streamStatus.isActive() && channelStatuses[channelIndex].streamStatus.isIdle()) {
			// handle idle -> active toggle for the input channel
			channelStatuses[channelIndex].streamStatus = StreamStatus.ACTIVE;

			// if the last watermark of the input channel, before it was marked idle, is still larger than
			// the overall last output watermark of the valve, then we can set the channel to be aligned already.
			if (channelStatuses[channelIndex].getWatermark() >= getLastOutputWatermark()) {
				channelStatuses[channelIndex].isWatermarkAligned = true;
			}

			// if the valve was previously marked to be idle, mark it as active and output an active stream
			// status because at least one of the input channels is now active
			if (lastOutputStreamStatus.isIdle()) {
				lastOutputStreamStatus = StreamStatus.ACTIVE;
				output.emitStreamStatus(lastOutputStreamStatus);
			}
		}
	}

	private void findAndOutputNewMinWatermarkAcrossAlignedChannels(Watermark watermark) throws Exception {
		long newMinWatermark = Long.MAX_VALUE;
		String key = null;
		boolean hasAlignedChannels = false;

		// determine new overall watermark by considering only watermark-aligned channels across all channels
		for (InputChannelStatus channelStatus : channelStatuses) {
			if (channelStatus.isWatermarkAligned) {
				hasAlignedChannels = true;
				if(channelStatus.getWatermarkByWatermark(watermark).getTimestamp() < newMinWatermark){
					newMinWatermark = Math.min(channelStatus.getWatermarkByWatermark(watermark).getTimestamp(), newMinWatermark);
					key = channelStatus.getWatermarkByWatermark(watermark).getKey();
				}

			}
		}

		// we acknowledge and output the new overall watermark if it really is aggregated
		// from some remaining aligned channel, and is also larger than the last output watermark
		if (hasAlignedChannels && newMinWatermark > getLastOutputWatermark(watermark)) {
			setLastOutputWatermark(new Watermark(newMinWatermark, key));
			output.emitWatermark(new Watermark(newMinWatermark, key));
		}
	}

	private void findAndOutputMaxWatermarkAcrossAllChannels() throws Exception {
		long maxWatermark = Long.MIN_VALUE;

		for (InputChannelStatus channelStatus : channelStatuses) {
			maxWatermark = Math.max(channelStatus.watermark, maxWatermark);
		}

		if (maxWatermark > getLastOutputWatermark()) {
			setLastOutputWatermark(maxWatermark);
			output.emitWatermark(new Watermark(lastOutputWatermark));
		}
	}

	/**
	 * An {@code InputChannelStatus} keeps track of an input channel's last watermark, stream
	 * status, and whether or not the channel's current watermark is aligned with the overall
	 * watermark output from the valve.
	 *
	 * <p>There are 2 situations where a channel's watermark is not considered aligned:
	 * <ul>
	 *   <li>the current stream status of the channel is idle
	 *   <li>the stream status has resumed to be active, but the watermark of the channel hasn't
	 *   caught up to the last output watermark from the valve yet.
	 * </ul>
	 */
	@VisibleForTesting
	protected static class InputChannelStatus {
		protected long watermark;
		protected Map<String, Watermark> watermarkMap = new ConcurrentHashMap<>();
		protected StreamStatus streamStatus;
		protected boolean isWatermarkAligned;

		public long getWatermarkTimestampByWatermark(Watermark watermark){
			return this.getWatermarkByWatermark(watermark).getTimestamp();
		}

		public void setWatermark(long watermark){
			this.watermark = watermark;
		}

		public void setWatermark(Watermark watermark){
			if(null == watermark.getKey() || "".equals(watermark.getKey())) {
				this.setWatermark(watermark.getTimestamp());
			}else{
				this.watermarkMap.put(watermark.getKey(), watermark);
			}
		}

		public long getWatermark(){
			return this.watermark;
		}

		public Watermark getWatermarkByWatermark(Watermark watermark){
			if(watermark == null){
				return new Watermark(this.getWatermark());
			}
			if(null == watermark.getKey() || "".equals(watermark.getKey())){
				return new Watermark(this.watermark);
			}else{
				Watermark watermark1 = watermarkMap.get(watermark.getKey());
				if(watermark1 == null){
					return new Watermark(Long.MIN_VALUE, watermark.getKey());
				}
				return watermark1;
			}
		}

		/**
		 * Utility to check if at least one channel in a given array of input channels is active.
		 */
		private static boolean hasActiveChannels(InputChannelStatus[] channelStatuses) {
			for (InputChannelStatus status : channelStatuses) {
				if (status.streamStatus.isActive()) {
					return true;
				}
			}
			return false;
		}
	}

	@VisibleForTesting
	protected InputChannelStatus getInputChannelStatus(int channelIndex) {
		Preconditions.checkArgument(
			channelIndex >= 0 && channelIndex < channelStatuses.length,
			"Invalid channel index. Number of input channels: " + channelStatuses.length);

		return channelStatuses[channelIndex];
	}
}
