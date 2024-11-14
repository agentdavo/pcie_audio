package audio

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

object SimulationHelpers {
  // Audio data generation
  case class AudioSample(
    value: BigInt,
    channel: Int,
    timestamp: Long
  )
  
  class AudioDataGenerator(channelCount: Int, sampleWidth: Int) {
    private val random = new Random()
    
    // Generate sine wave samples
    def generateSineWave(frequency: Double, sampleRate: Double, duration: Double): Seq[AudioSample] = {
      val samples = ArrayBuffer[AudioSample]()
      val samplesCount = (duration * sampleRate).toInt
      val maxValue = (1 << (sampleWidth - 1)) - 1
      
      for {
        i <- 0 until samplesCount
        channel <- 0 until channelCount
      } {
        val time = i.toDouble / sampleRate
        val value = (Math.sin(2 * Math.PI * frequency * time) * maxValue).toInt
        samples += AudioSample(value, channel, i)
      }
      
      samples
    }
    
    // Generate DSD bitstream
    def generateDSDStream(durationBits: Int): Seq[Boolean] = {
      val stream = ArrayBuffer[Boolean]()
      var accumulator = 0.0
      
      for(_ <- 0 until durationBits) {
        // Simple noise shaping
        val input = random.nextDouble() * 2 - 1
        accumulator = accumulator + input
        val bit = accumulator >= 0
        accumulator = accumulator - (if(bit) 1.0 else -1.0)
        stream += bit
      }
      
      stream
    }
    
    // Generate test patterns
    def generateTestPattern(pattern: String, length: Int): Seq[AudioSample] = {
      val samples = ArrayBuffer[AudioSample]()
      
      pattern match {
        case "ramp" =>
          for {
            i <- 0 until length
            channel <- 0 until channelCount
          } {
            val value = (i % (1 << sampleWidth)) - (1 << (sampleWidth - 1))
            samples += AudioSample(value, channel, i)
          }
          
        case "alternating" =>
          for {
            i <- 0 until length
            channel <- 0 until channelCount
          } {
            val value = if(i % 2 == 0) (1 << (sampleWidth - 1)) - 1 else -(1 << (sampleWidth - 1))
            samples += AudioSample(value, channel, i)
          }
          
        case "dc" =>
          for {
            i <- 0 until length
            channel <- 0 until channelCount
          } {
            val value = (1 << (sampleWidth - 2))  // 1/4 full scale
            samples += AudioSample(value, channel, i)
          }
          
        case _ => throw new IllegalArgumentException(s"Unknown test pattern: $pattern")
      }
      
      samples
    }
  }
  
  // DMA helpers
  class DMAHelper(dut: AudioPCIeTop) {
    case class DMADescriptor(
      address: BigInt,
      length: Int,
      isLast: Boolean,
      interrupt: Boolean
    )
    
    def setupDescriptorRing(baseAddr: BigInt, descriptors: Seq[DMADescriptor]): Unit = {
      for((desc, index) <- descriptors.zipWithIndex) {
        val nextAddr = if(index == descriptors.length - 1) baseAddr else baseAddr + ((index + 1) * 16)
        
        // Write descriptor to simulated memory
        writeMem(baseAddr + (index * 16) + 0, desc.address)
        writeMem(baseAddr + (index * 16) + 8, desc.length)
        writeMem(baseAddr + (index * 16) + 12, 
                (if(desc.isLast) (1 << 1) else 0) | 
                (if(desc.interrupt) 1 else 0))
        writeMem(baseAddr + (index * 16) + 16, nextAddr)
      }
    }
    
    def writeMem(address: BigInt, data: BigInt): Unit = {
      dut.clockDomain.waitSampling()
      dut.io.pcie.tx.valid #= true
      dut.io.pcie.tx.data #= data
      while(!dut.io.pcie.tx.ready.toBoolean) {
        dut.clockDomain.waitSampling()
      }
      dut.clockDomain.waitSampling()
      dut.io.pcie.tx.valid #= false
    }
    
    def verifyDMATransfer(startAddr: BigInt, expectedData: Seq[BigInt]): Boolean = {
      var match_found = true
      for((data, offset) <- expectedData.zipWithIndex) {
        val readData = readMem(startAddr + offset * 8)
        if(readData != data) {
          match_found = false
          println(s"DMA mismatch at offset $offset: expected $data, got $readData")
        }
      }
      match_found
    }
    
    private def readMem(address: BigInt): BigInt = {
      dut.clockDomain.waitSampling()
      dut.io.pcie.rx.valid #= true
      dut.io.pcie.rx.data.toBigInt
    }
  }
  
  // Clock generation helpers
  class ClockGenerator {
    def generateMclk(frequency: Double): (Boolean => Unit) = {
      val period = (1000000000.0 / frequency).toLong  // period in ns
      
      (clock: Boolean) => {
        sleep(period / 2)
      }
    }
    
    def generate44k1Family(): (Boolean => Unit) = {
      generateMclk(11289600)  // 256 * 44100
    }
    
    def generate48kFamily(): (Boolean => Unit) = {
      generateMclk(12288000)  // 256 * 48000
    }
  }
  
  // PCIe transaction helpers
  class PCIeTransactionHelper(dut: AudioPCIeTop) {
    def writeConfig(address: Int, data: BigInt): Unit = {
      dut.clockDomain.waitSampling()
      dut.io.pcie.cfg.write #= true
      dut.io.pcie.cfg.addr #= address
      dut.io.pcie.cfg.writeData #= data
      dut.clockDomain.waitSampling()
      dut.io.pcie.cfg.write #= false
    }
    
    def readConfig(address: Int): BigInt = {
      dut.clockDomain.waitSampling()
      dut.io.pcie.cfg.read #= true
      dut.io.pcie.cfg.addr #= address
      dut.clockDomain.waitSampling()
      val data = dut.io.pcie.cfg.readData.toBigInt
      dut.io.pcie.cfg.read #= false
      data
    }
    
    def waitForInterrupt(timeout: Int = 1000): Boolean = {
      var count = 0
      while(!dut.io.interrupt.toBoolean && count < timeout) {
        dut.clockDomain.waitSampling()
        count += 1
      }
      dut.io.interrupt.toBoolean
    }
  }
  
  // Audio monitoring and analysis
  class AudioAnalyzer(channelCount: Int, sampleWidth: Int) {
    case class ChannelStats(
      peakLevel: Double,
      rmsLevel: Double,
      dcOffset: Double
    )
    
    def analyzeSamples(samples: Seq[AudioSample]): Map[Int, ChannelStats] = {
      val channelSamples = samples.groupBy(_.channel)
      
      channelSamples.map { case (channel, channelData) =>
        val values = channelData.map(_.value.toDouble / (1 << (sampleWidth - 1)))
        
        val peak = values.map(Math.abs).max
        val rms = Math.sqrt(values.map(x => x * x).sum / values.length)
        val dc = values.sum / values.length
        
        channel -> ChannelStats(peak, rms, dc)
      }
    }
    
    def detectDropouts(samples: Seq[AudioSample]): Seq[(Int, Long)] = {
      val dropouts = ArrayBuffer[(Int, Long)]()
      
      for(channel <- 0 until channelCount) {
        val channelSamples = samples.filter(_.channel == channel).sortBy(_.timestamp)
        var lastTimestamp = channelSamples.head.timestamp
        
        for(sample <- channelSamples.tail) {
          if(sample.timestamp - lastTimestamp > 1) {
            dropouts += ((channel, lastTimestamp))
          }
          lastTimestamp = sample.timestamp
        }
      }
      
      dropouts
    }
  }
  
  // Performance monitoring
  class PerformanceMonitor {
    case class PerformanceMetrics(
      dmaTransferRate: Double,
      averageLatency: Double,
      peakLatency: Double,
      underruns: Int,
      overruns: Int
    )
    
    private var startTime: Long = 0
    private var transferCount: Int = 0
    private var latencies = ArrayBuffer[Double]()
    private var underrunCount: Int = 0
    private var overrunCount: Int = 0
    
    def start(): Unit = {
      startTime = System.nanoTime()
      transferCount = 0
      latencies.clear()
      underrunCount = 0
      overrunCount = 0
    }
    
    def recordTransfer(latencyUs: Double): Unit = {
      transferCount += 1
      latencies += latencyUs
    }
    
    def recordError(isUnderrun: Boolean): Unit = {
      if(isUnderrun) underrunCount += 1 else overrunCount += 1
    }
    
    def getMetrics(): PerformanceMetrics = {
      val duration = (System.nanoTime() - startTime) / 1e9
      val transferRate = transferCount.toDouble / duration
      
      PerformanceMetrics(
        dmaTransferRate = transferRate,
        averageLatency = if(latencies.nonEmpty) latencies.sum / latencies.size else 0,
        peakLatency = if(latencies.nonEmpty) latencies.max else 0,
        underruns = underrunCount,
        overruns = overrunCount
      )
    }
  }
}