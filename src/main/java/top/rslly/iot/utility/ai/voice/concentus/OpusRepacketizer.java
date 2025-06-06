/**
 * Copyright © 2023-2030 The ruanrongman Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package top.rslly.iot.utility.ai.voice.concentus;

public class OpusRepacketizer {

  byte toc = 0;
  int nb_frames = 0;
  final byte[][] frames = new byte[48][];
  final short[] len = new short[48];
  int framesize = 0;

  /**
   * (Re)initializes a previously allocated repacketizer state. The state must be at least the size
   * returned by opus_repacketizer_get_size(). This can be used for applications which use their own
   * allocator instead of malloc(). It must also be called to reset the queue of packets waiting to
   * be repacketized, which is necessary if the maximum packet duration of 120 ms is reached or if
   * you wish to submit packets with a different Opus configuration (coding mode, audio bandwidth,
   * frame size, or channel count). Failure to do so will prevent a new packet from being added with
   * opus_repacketizer_cat().
   *
   * @see opus_repacketizer_create
   * @see opus_repacketizer_get_size
   * @see opus_repacketizer_cat
   * @param rp <tt>OpusRepacketizer*</tt>: The repacketizer state to (re)initialize.
   */
  public void Reset() {
    this.nb_frames = 0;
  }

  /**
   * Allocates memory and initializes the new repacketizer with opus_repacketizer_init().
   */
  public OpusRepacketizer() {
    this.Reset();
  }

  int opus_repacketizer_cat_impl(byte[] data, int data_ptr, int len, int self_delimited) {
    BoxedValueByte dummy_toc = new BoxedValueByte((byte) 0);
    BoxedValueInt dummy_offset = new BoxedValueInt(0);
    int curr_nb_frames, ret;
    /* Set of check ToC */
    if (len < 1) {
      return OpusError.OPUS_INVALID_PACKET;
    }

    if (this.nb_frames == 0) {
      this.toc = data[data_ptr];
      this.framesize = OpusPacketInfo.getNumSamplesPerFrame(data, data_ptr, 8000);
    } else if ((this.toc & 0xFC) != (data[data_ptr] & 0xFC)) {
      /* fprintf(stderr, "toc mismatch: 0x%x vs 0x%x\n", rp.toc, data[0]); */
      return OpusError.OPUS_INVALID_PACKET;
    }
    curr_nb_frames = OpusPacketInfo.getNumFrames(data, data_ptr, len);
    if (curr_nb_frames < 1) {
      return OpusError.OPUS_INVALID_PACKET;
    }

    /* Check the 120 ms maximum packet size */
    if ((curr_nb_frames + this.nb_frames) * this.framesize > 960) {
      return OpusError.OPUS_INVALID_PACKET;
    }

    ret = OpusPacketInfo.opus_packet_parse_impl(data, data_ptr, len, self_delimited, dummy_toc,
        this.frames, this.nb_frames, this.len, this.nb_frames, dummy_offset, dummy_offset);
    if (ret < 1) {
      return ret;
    }

    this.nb_frames += curr_nb_frames;
    return OpusError.OPUS_OK;
  }

  /**
   * opus_repacketizer_cat. Add a packet to the current repacketizer state. This packet must match
   * the configuration of any packets already submitted for repacketization since the last call to
   * opus_repacketizer_init(). This means that it must have the same coding mode, audio bandwidth,
   * frame size, and channel count. This can be checked in advance by examining the top 6 bits of
   * the first byte of the packet, and ensuring they match the top 6 bits of the first byte of any
   * previously submitted packet. The total duration of audio in the repacketizer state also must
   * not exceed 120 ms, the maximum duration of a single packet, after adding this packet.
   *
   * The contents of the current repacketizer state can be extracted into new packets using
   * opus_repacketizer_out() or opus_repacketizer_out_range().
   *
   * In order to add a packet with a different configuration or to add more audio beyond 120 ms, you
   * must clear the repacketizer state by calling opus_repacketizer_init(). If a packet is too large
   * to add to the current repacketizer state, no part of it is added, even if it contains multiple
   * frames, some of which might fit. If you wish to be able to add parts of such packets, you
   * should first use another repacketizer to split the packet into pieces and add them
   * individually.
   *
   * @see opus_repacketizer_out_range
   * @see opus_repacketizer_out
   * @see opus_repacketizer_init
   * @param data : The packet data. The application must ensure this pointer remains valid until the
   *        next call to opus_repacketizer_init() or opus_repacketizer_destroy().
   * @param len: The number of bytes in the packet data.
   * @returns An error code indicating whether or not the operation succeeded.
   * @retval #OPUS_OK The packet's contents have been added to the repacketizer state.
   * @retval #OPUS_INVALID_PACKET The packet did not have a valid TOC sequence, the packet's TOC
   *         sequence was not compatible with previously submitted packets (because the coding mode,
   *         audio bandwidth, frame size, or channel count did not match), or adding this packet
   *         would increase the total amount of audio stored in the repacketizer state to more than
   *         120 ms.
   */
  public int addPacket(byte[] data, int data_offset, int len) {
    return opus_repacketizer_cat_impl(data, data_offset, len, 0);
  }

  /**
   * Return the total number of frames contained in packet data submitted to the repacketizer state
   * so far via opus_repacketizer_cat() since the last call to opus_repacketizer_init() or
   * opus_repacketizer_create(). This defines the valid range of packets that can be extracted with
   * opus_repacketizer_out_range() or opus_repacketizer_out().
   *
   * @param rp <tt>OpusRepacketizer*</tt>: The repacketizer state containing the frames.
   * @returns The total number of frames contained in the packet data submitted to the repacketizer
   *          state.
   */
  public int getNumFrames() {
    return this.nb_frames;
  }

  int opus_repacketizer_out_range_impl(int begin, int end,
      byte[] data, int data_ptr, int maxlen, int self_delimited, int pad) {
    int i, count;
    int tot_size;
    int ptr;

    if (begin < 0 || begin >= end || end > this.nb_frames) {
      /* fprintf(stderr, "%d %d %d\n", begin, end, rp.nb_frames); */
      return OpusError.OPUS_BAD_ARG;
    }
    count = end - begin;

    if (self_delimited != 0) {
      tot_size = 1 + (this.len[count - 1] >= 252 ? 1 : 0);
    } else {
      tot_size = 0;
    }

    ptr = data_ptr;
    if (count == 1) {
      /* Code 0 */
      tot_size += this.len[0] + 1;
      if (tot_size > maxlen) {
        return OpusError.OPUS_BUFFER_TOO_SMALL;
      }
      data[ptr++] = (byte) (this.toc & 0xFC);
    } else if (count == 2) {
      if (this.len[1] == this.len[0]) {
        /* Code 1 */
        tot_size += 2 * this.len[0] + 1;
        if (tot_size > maxlen) {
          return OpusError.OPUS_BUFFER_TOO_SMALL;
        }
        data[ptr++] = (byte) ((this.toc & 0xFC) | 0x1);
      } else {
        /* Code 2 */
        tot_size += this.len[0] + this.len[1] + 2 + (this.len[0] >= 252 ? 1 : 0);
        if (tot_size > maxlen) {
          return OpusError.OPUS_BUFFER_TOO_SMALL;
        }
        data[ptr++] = (byte) ((this.toc & 0xFC) | 0x2);
        ptr += OpusPacketInfo.encode_size(this.len[0], data, ptr);
      }
    }
    if (count > 2 || (pad != 0 && tot_size < maxlen)) {
      /* Code 3 */
      int vbr;
      int pad_amount = 0;

      /* Restart the process for the padding case */
      ptr = data_ptr;
      if (self_delimited != 0) {
        tot_size = 1 + (this.len[count - 1] >= 252 ? 1 : 0);
      } else {
        tot_size = 0;
      }
      vbr = 0;
      for (i = 1; i < count; i++) {
        if (this.len[i] != this.len[0]) {
          vbr = 1;
          break;
        }
      }
      if (vbr != 0) {
        tot_size += 2;
        for (i = 0; i < count - 1; i++) {
          tot_size += 1 + (this.len[i] >= 252 ? 1 : 0) + this.len[i];
        }
        tot_size += this.len[count - 1];

        if (tot_size > maxlen) {
          return OpusError.OPUS_BUFFER_TOO_SMALL;
        }
        data[ptr++] = (byte) ((this.toc & 0xFC) | 0x3);
        data[ptr++] = (byte) (count | 0x80);
      } else {
        tot_size += count * this.len[0] + 2;
        if (tot_size > maxlen) {
          return OpusError.OPUS_BUFFER_TOO_SMALL;
        }
        data[ptr++] = (byte) ((this.toc & 0xFC) | 0x3);
        data[ptr++] = (byte) (count);
      }

      pad_amount = pad != 0 ? (maxlen - tot_size) : 0;

      if (pad_amount != 0) {
        int nb_255s;
        data[data_ptr + 1] = (byte) (data[data_ptr + 1] | 0x40);
        nb_255s = (pad_amount - 1) / 255;
        for (i = 0; i < nb_255s; i++) {
          data[ptr++] = -1;
        }

        data[ptr++] = (byte) (pad_amount - 255 * nb_255s - 1);
        tot_size += pad_amount;
      }

      if (vbr != 0) {
        for (i = 0; i < count - 1; i++) {
          ptr += (OpusPacketInfo.encode_size(this.len[i], data, ptr));
        }
      }
    }

    if (self_delimited != 0) {
      int sdlen = OpusPacketInfo.encode_size(this.len[count - 1], data, ptr);
      ptr += (sdlen);
    }

    /* Copy the actual data */
    for (i = begin; i < count + begin; i++) {

      if (data == this.frames[i]) {
        /*
         * Using OPUS_MOVE() instead of OPUS_COPY() in case we're doing in-place padding from
         * opus_packet_pad or opus_packet_unpad().
         */
        Arrays.MemMove(data, 0, ptr, this.len[i]);
      } else {
        System.arraycopy(this.frames[i], 0, data, ptr, this.len[i]);
      }
      ptr += this.len[i];
    }

    if (pad != 0) {
      /* Fill padding with zeros. */
      Arrays.MemSetWithOffset(data, (byte) 0, ptr, data_ptr + maxlen - ptr);
    }

    return tot_size;
  }

  /**
   * Construct a new packet from data previously submitted to the repacketizer state via
   * opus_repacketizer_cat().
   *
   * @param rp <tt>OpusRepacketizer*</tt>: The repacketizer state from which to construct the new
   *        packet.
   * @param begin <tt>int</tt>: The index of the first frame in the current repacketizer state to
   *        include in the output.
   * @param end <tt>int</tt>: One past the index of the last frame in the current repacketizer state
   *        to include in the output.
   * @param[out] data <tt>final unsigned char*</tt>: The buffer in which to store the output packet.
   * @param maxlen <tt>opus_int32</tt>: The maximum number of bytes to store in the output buffer.
   *        In order to guarantee success, this should be at least <code>1276</code> for a single
   *        frame, or for multiple frames, <code>1277*(end-begin)</code>. However,
   *        <code>1*(end-begin)</code> plus the size of all packet data submitted to the
   *        repacketizer since the last call to opus_repacketizer_init() or
   *        opus_repacketizer_create() is also sufficient, and possibly much smaller.
   * @returns The total size of the output packet on success, or an error code on failure.
   * @retval #OPUS_BAD_ARG <code>[begin,end)</code> was an invalid range of frames (begin < 0, begin
   *         >= end, or end > opus_repacketizer_get_nb_frames()).
   * @retval #OPUS_BUFFER_TOO_SMALL \a maxlen was insufficient to contain the complete output
   *         packet.
   */
  public int createPacket(int begin, int end, byte[] data, int data_offset, int maxlen) {
    return opus_repacketizer_out_range_impl(begin, end, data, data_offset, maxlen, 0, 0);
  }

  /**
   * Construct a new packet from data previously submitted to the repacketizer state via
   * opus_repacketizer_cat(). This is a convenience routine that returns all the data submitted so
   * far in a single packet. It is equivalent to calling
   * 
   * @code opus_repacketizer_out_range(rp, 0, opus_repacketizer_get_nb_frames(rp), data,
   *       maxlen) @endcode @param rp <tt>OpusRepacketizer*</tt>: The repacketizer state from which
   *       to construct the new packet. @param[out] data <tt>final unsigned char*</tt>: The buffer
   *       in which to store the output packet. @param maxlen <tt>opus_int32</tt>: The maximum
   *       number of bytes to store in the output buffer. In order to guarantee success, this should
   *       be at least <code>1277*opus_repacketizer_get_nb_frames(rp)</code>. However,
   *       <code>1*opus_repacketizer_get_nb_frames(rp)</code> plus the size of all packet data
   *       submitted to the repacketizer since the last call to opus_repacketizer_init() or
   *       opus_repacketizer_create() is also sufficient, and possibly much smaller. @returns The
   *       total size of the output packet on success, or an error code on failure. @retval
   *       #OPUS_BUFFER_TOO_SMALL \a maxlen was insufficient to contain the complete output packet.
   */
  public int createPacket(byte[] data, int data_offset, int maxlen) {
    return opus_repacketizer_out_range_impl(0, this.nb_frames, data, data_offset, maxlen, 0, 0);
  }

  /**
   * Pads a given Opus packet to a larger size (possibly changing the TOC sequence).
   *
   * @param[in,out] data <tt>final unsigned char*</tt>: The buffer containing the packet to pad.
   * @param len <tt>opus_int32</tt>: The size of the packet. This must be at least 1.
   * @param new_len <tt>opus_int32</tt>: The desired size of the packet after padding. This must be
   *        at least as large as len.
   * @returns an error code
   * @retval #OPUS_OK \a on success.
   * @retval #OPUS_BAD_ARG \a len was less than 1 or new_len was less than len.
   * @retval #OPUS_INVALID_PACKET \a data did not contain a valid Opus packet.
   */
  public static int padPacket(byte[] data, int data_offset, int len, int new_len) {
    OpusRepacketizer rp = new OpusRepacketizer();
    int ret;
    if (len < 1) {
      return OpusError.OPUS_BAD_ARG;
    }
    if (len == new_len) {
      return OpusError.OPUS_OK;
    } else if (len > new_len) {
      return OpusError.OPUS_BAD_ARG;
    }
    rp.Reset();
    /* Moving payload to the end of the packet so we can do in-place padding */
    Arrays.MemMove(data, data_offset, data_offset + new_len - len, len);
    // data.MemMoveTo(data.Point(new_len - len), len);
    rp.addPacket(data, data_offset + new_len - len, len);
    ret = rp.opus_repacketizer_out_range_impl(0, rp.nb_frames, data, data_offset, new_len, 0, 1);
    if (ret > 0) {
      return OpusError.OPUS_OK;
    } else {
      return ret;
    }
  }

  /**
   * Remove all padding from a given Opus packet and rewrite the TOC sequence to minimize space
   * usage.
   *
   * @param[in,out] data <tt>final unsigned char*</tt>: The buffer containing the packet to strip.
   * @param len <tt>opus_int32</tt>: The size of the packet. This must be at least 1.
   * @returns The new size of the output packet on success, or an error code on failure.
   * @retval #OPUS_BAD_ARG \a len was less than 1.
   * @retval #OPUS_INVALID_PACKET \a data did not contain a valid Opus packet.
   */
  public static int unpadPacket(byte[] data, int data_offset, int len) {
    int ret;
    if (len < 1) {
      return OpusError.OPUS_BAD_ARG;
    }

    OpusRepacketizer rp = new OpusRepacketizer();
    rp.Reset();
    ret = rp.addPacket(data, data_offset, len);
    if (ret < 0) {
      return ret;
    }
    ret = rp.opus_repacketizer_out_range_impl(0, rp.nb_frames, data, data_offset, len, 0, 0);
    Inlines.OpusAssert(ret > 0 && ret <= len);

    return ret;
  }

  /**
   * Pads a given Opus multi-stream packet to a larger size (possibly changing the TOC sequence).
   *
   * @param[in,out] data <tt>final unsigned char*</tt>: The buffer containing the packet to pad.
   * @param len <tt>opus_int32</tt>: The size of the packet. This must be at least 1.
   * @param new_len <tt>opus_int32</tt>: The desired size of the packet after padding. This must be
   *        at least 1.
   * @param nb_streams <tt>opus_int32</tt>: The number of streams (not channels) in the packet. This
   *        must be at least as large as len.
   * @returns an error code
   * @retval #OPUS_OK \a on success.
   * @retval #OPUS_BAD_ARG \a len was less than 1.
   * @retval #OPUS_INVALID_PACKET \a data did not contain a valid Opus packet.
   */
  public static int padMultistreamPacket(byte[] data, int data_offset, int len, int new_len,
      int nb_streams) {
    int s;
    int count;
    BoxedValueByte dummy_toc = new BoxedValueByte((byte) 0);
    short[] size = new short[48];
    BoxedValueInt packet_offset = new BoxedValueInt(0);
    BoxedValueInt dummy_offset = new BoxedValueInt(0);
    int amount;

    if (len < 1) {
      return OpusError.OPUS_BAD_ARG;
    }
    if (len == new_len) {
      return OpusError.OPUS_OK;
    } else if (len > new_len) {
      return OpusError.OPUS_BAD_ARG;
    }
    amount = new_len - len;
    /* Seek to last stream */
    for (s = 0; s < nb_streams - 1; s++) {
      if (len <= 0) {
        return OpusError.OPUS_INVALID_PACKET;
      }
      count = OpusPacketInfo.opus_packet_parse_impl(data, data_offset, len, 1, dummy_toc, null, 0,
          size, 0, dummy_offset, packet_offset);
      if (count < 0) {
        return count;
      }
      data_offset += packet_offset.Val;
      len -= packet_offset.Val;
    }
    return padPacket(data, data_offset, len, len + amount);
  }

  // FIXME THIS METHOD FAILS IN TEST_OPUS_ENCODE
  /**
   * Remove all padding from a given Opus multi-stream packet and rewrite the TOC sequence to
   * minimize space usage.
   *
   * @param[in,out] data <tt>final unsigned char*</tt>: The buffer containing the packet to strip.
   * @param len <tt>opus_int32</tt>: The size of the packet. This must be at least 1.
   * @param nb_streams <tt>opus_int32</tt>: The number of streams (not channels) in the packet. This
   *        must be at least 1.
   * @returns The new size of the output packet on success, or an error code on failure.
   * @retval #OPUS_BAD_ARG \a len was less than 1 or new_len was less than len.
   * @retval #OPUS_INVALID_PACKET \a data did not contain a valid Opus packet.
   */
  public static int unpadMultistreamPacket(byte[] data, int data_offset, int len, int nb_streams) {
    int s;
    BoxedValueByte dummy_toc = new BoxedValueByte((byte) 0);
    short[] size = new short[48];
    BoxedValueInt packet_offset = new BoxedValueInt(0);
    BoxedValueInt dummy_offset = new BoxedValueInt(0);
    OpusRepacketizer rp = new OpusRepacketizer();
    int dst;
    int dst_len;

    if (len < 1) {
      return OpusError.OPUS_BAD_ARG;
    }
    dst = data_offset;
    dst_len = 0;
    /* Unpad all frames */
    for (s = 0; s < nb_streams; s++) {
      int ret;
      int self_delimited = ((s != nb_streams) ? 1 : 0) - 1;
      if (len <= 0) {
        return OpusError.OPUS_INVALID_PACKET;
      }
      rp.Reset();
      ret = OpusPacketInfo.opus_packet_parse_impl(data, data_offset, len, self_delimited, dummy_toc,
          null, 0,
          size, 0, dummy_offset, packet_offset);
      if (ret < 0) {
        return ret;
      }
      ret = rp.opus_repacketizer_cat_impl(data, data_offset, packet_offset.Val, self_delimited);
      if (ret < 0) {
        return ret;
      }
      ret = rp.opus_repacketizer_out_range_impl(0, rp.nb_frames, data, dst, len, self_delimited, 0);
      if (ret < 0) {
        return ret;
      } else {
        dst_len += ret;
      }
      dst += ret;
      data_offset += packet_offset.Val;
      len -= packet_offset.Val;
    }
    return dst_len;
  }
}
