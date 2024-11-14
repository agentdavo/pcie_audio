package audio

object AudioPCIeTopVerilog {
  def main(args: Array[String]) {
    SpinalVerilog(new AudioPCIeTop(
      AudioConfig(
        channelCount = 8,
        i2sDataWidth = 24,
        dsdBitWidth = 1,
        useMultipleClocks = true,
        supportDsd = true,
        bufferSize = 8192,
        bufferCount = 4,
        maxBurstSize = 512,
        fifoDepth = 1024,
        dmaDescriptorCount = 32
      )
    ))
  }
}