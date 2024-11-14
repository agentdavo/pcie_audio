package audio

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import scala.collection.mutable.ArrayBuffer
import org.scalatest.funsuite.AnyFunSuite
import SimulationHelpers._

class PerformanceTest extends AnyFunSuite {
  val testConfig = AudioConfig(
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

  test("DMA Performance Test") {
    SimConfig.withWave.compile(new AudioPCIeTop(testConfig)).doSim { dut =>
      val perfMonitor = new PerformanceMonitor()
      val dmaHelper = new DMAHelper(dut)
      val pcieHelper = new PCIeTransactionHelper(dut)
      
      // Initialize
      dut.clockDomain.forkStimulus(10)
      
      // Setup test scenario
      val descriptorCount = 32
      val bufferSize = 4096
      val totalDataSize = descriptorCount * bufferSize
      
      // Create descriptor ring
      val descriptors = for(i <- 0 until descriptorCount) yield {
        DMADescriptor(
          address = 0x1000000 + (i * bufferSize),
          length = bufferSize,
          isLast = i == descriptorCount - 1,
          interrupt = i % 4 == 3  // Interrupt every 4 descriptors
        )
      }
      
      // Start performance monitoring
      perfMonitor.start()
      
      // Setup DMA
      dmaHelper.setupDescriptorRing(0x10000, descriptors)
      
      // Configure device
      pcieHelper.writeConfig(0x100, 0x10000)      // Descriptor base
      pcieHelper.writeConfig(0x108, descriptorCount) // Descriptor count
      pcieHelper.writeConfig(0x110, bufferSize)   // Buffer size
      pcieHelper.writeConfig(0x114, 1)            // Enable interrupts
      
      // Enable DMA
      pcieHelper.writeConfig(0x018, 1)
      
      // Run test
      var completedTransfers = 0
      while(completedTransfers < descriptorCount) {
        if(pcieHelper.waitForInterrupt(1000)) {
          val latency = pcieHelper.readConfig(0x500) // Read latency counter
          perfMonitor.recordTransfer(latency.toDouble)
          completedTransfers += 1
          
          // Check for errors
          val status = pcieHelper.readConfig(0x30C)
          if((status & 1) != 0) {
            perfMonitor.recordError(isUnderrun = true)
          }
        }
      }
      
      // Get and verify results
      val metrics = perfMonitor.getMetrics()
      println(s"""DMA Performance Results:
                 |Transfer Rate: ${metrics.dmaTransferRate} transfers/sec
                 |Average Latency: ${metrics.averageLatency} us
                 |Peak Latency: ${metrics.peakLatency} us
                 |Underruns: ${metrics.underruns}
                 |Data Throughput: ${metrics.dmaTransferRate * bufferSize / (1024*1024)} MB/s
                 |""".stripMargin)
                 
      assert(metrics.dmaTransferRate > 100, "DMA transfer rate too low")
      assert(metrics.averageLatency < 1000, "Average latency too high")
      assert(metrics.underruns == 0, "DMA underruns detected")
    }
  }
  
  test("Audio Quality Test") {
    SimConfig.withWave.compile(new AudioPCIeTop(testConfig)).doSim { dut =>
      val audioGen = new AudioDataGenerator(testConfig.channelCount, testConfig.i2sDataWidth)
      val analyzer = new AudioAnalyzer(testConfig.channelCount, testConfig.i2sDataWidth)
      val dmaHelper = new DMAHelper(dut)
      
      // Initialize
      dut.clockDomain.forkStimulus(10)
      
      // Generate test signals
      val sineWave = audioGen.generateSineWave(1000, 48000, 0.1) // 1kHz test tone
      
      // Setup DMA with test data
      val baseAddr = 0x1000000
      dmaHelper.writeMem(baseAddr, sineWave.map(_.value))
      
      // Configure for I2S playback
      val pcieHelper = new PCIeTransactionHelper(dut)
      pcieHelper.writeConfig(0x000, 0) // I2S mode
      pcieHelper.writeConfig(0x004, 1) // 48kHz
      pcieHelper.writeConfig(0x014, 1) // Master mode
      
      // Capture output samples
      val capturedSamples = ArrayBuffer[AudioSample]()
      var sampleCount = 0
      
      fork {
        while(sampleCount < sineWave.length) {
          dut.clockDomain.waitSampling()
          if(dut.io.audio.i2s.ws.toBoolean) {
            for(ch <- 0 until testConfig.channelCount) {
              capturedSamples += AudioSample(
                dut.io.audio.i2s.sd(ch).toBigInt,
                ch,
                sampleCount
              )
            }
            sampleCount += 1
          }
        }
      }
      
      // Run simulation
      dut.clockDomain.waitSampling(sineWave.length * 100)
      
      // Analyze results
      val stats = analyzer.analyzeSamples(capturedSamples)
      val dropouts = analyzer.detectDropouts(capturedSamples)
      
      println("Audio Quality Analysis:")
      stats.foreach { case (channel, stats) =>
        println(s"""Channel $channel:
                   |  Peak Level: ${stats.peakLevel} dBFS
                   |  RMS Level: ${stats.rmsLevel} dBFS
                   |  DC Offset: ${stats.dcOffset} dBFS
                   |""".stripMargin)
      }
      
      if(dropouts.nonEmpty) {
        println("Dropouts detected:")
        dropouts.foreach { case (channel, timestamp) =>
          println(s"Channel $channel at sample $timestamp")
        }
      }
      
      // Verify results
      assert(dropouts.isEmpty, "Audio dropouts detected")
      stats.foreach { case (_, stats) =>
        assert(stats.peakLevel <= 0.0, "Clipping detected")
        assert(Math.abs(stats.dcOffset) < 0.001, "Significant DC offset detected")
      }
    }
  }
  
  test("Clock Stability Test") {
    SimConfig.withWave.compile(new AudioPCIeTop(testConfig)).doSim { dut =>
      val clockGen = new ClockGenerator()
      
      // Initialize
      dut.clockDomain.forkStimulus(10)
      
      // Setup clock generators
      fork {
        val mclk44k1 = clockGen.generate44k1Family()
        while(true) {
          dut.io.audio.mclk44k1 #= true
          mclk44k1(true)
          dut.io.audio.mclk44k1 #= false
          mclk44k1(false)
        }
      }
      
      fork {
        val mclk48k = clockGen.generate48kFamily()
        while(true) {
          dut.io.audio.mclk48k #= true
          mclk48k(true)
          dut.io.audio.mclk48k #= false
          mclk48k(false)
        }
      }
      
      // Test clock switching
      val pcieHelper = new PCIeTransactionHelper(dut)
      val clockUnlocks = ArrayBuffer[Int]()
      var switchCount = 0
      
      // Monitor clock status
      fork {
        var lastLocked = true
        while(switchCount < 10) {
          dut.clockDomain.waitSampling()
          val locked = (pcieHelper.readConfig(0x300) & 1) == 1
          if(lastLocked && !locked) {
            clockUnlocks += switchCount
          }
          lastLocked = locked
        }
      }
      
      // Perform clock switching
      for(i <- 0 until 10) {
        // Switch between 44.1kHz and 48kHz
        pcieHelper.writeConfig(0x004, i % 2)
        dut.clockDomain.waitSampling(1000)
        switchCount += 1
      }
      
      println(s"Clock unlock events: ${clockUnlocks.size}")
      clockUnlocks.foreach { switch =>
        println(s"Unlock at switch $switch")
      }
      
      // Verify results
      assert(clockUnlocks.size <= 2, "Too many clock unlock events")
    }
  }
  
  test("Load Test") {
    SimConfig.withWave.compile(new AudioPCIeTop(testConfig)).doSim { dut =>
      val perfMonitor = new PerformanceMonitor()
      val dmaHelper = new DMAHelper(dut)
      val pcieHelper = new PCIeTransactionHelper(dut)
      
      // Initialize
      dut.clockDomain.forkStimulus(10)
      
      // Setup maximum load test
      val descriptorCount = 32
      val bufferSize = 8192  // Maximum buffer size
      
      // Create and setup descriptors
      val descriptors = for(i <- 0 until descriptorCount) yield {
        DMADescriptor(
          address = 0x1000000 + (i * bufferSize),
          length = bufferSize,
          isLast = i == descriptorCount - 1,
          interrupt = true  // Interrupt on every descriptor
        )
      }
      
      perfMonitor.start()
      
      // Setup both playback and capture
      dmaHelper.setupDescriptorRing(0x10000, descriptors) // Playback
      dmaHelper.setupDescriptorRing(0x20000, descriptors) // Capture
      
      // Configure for maximum throughput
      pcieHelper.writeConfig(0x000, 0)     // I2S mode
      pcieHelper.writeConfig(0x004, 1)     // 48kHz
      pcieHelper.writeConfig(0x008, 2)     // 4x rate (192kHz)
      pcieHelper.writeConfig(0x038, 4096)  // Large buffer threshold
      pcieHelper.writeConfig(0x03C, 4096)  // Large buffer threshold
      
      // Enable both directions
      pcieHelper.writeConfig(0x018, 1)     // Enable playback
      pcieHelper.writeConfig(0x01C, 1)     // Enable capture
      
      // Run test
      var completedTransfers = 0
      val startTime = System.nanoTime()
      
      while(completedTransfers < 1000) {  // Run for 1000 transfers
        if(pcieHelper.waitForInterrupt(100)) {
          val pbStatus = pcieHelper.readConfig(0x30C)
          val capStatus = pcieHelper.readConfig(0x310)
          
          if((pbStatus & 1) != 0) perfMonitor.recordError(true)
          if((capStatus & 1) != 0) perfMonitor.recordError(false)
          
          completedTransfers += 1
          perfMonitor.recordTransfer(System.nanoTime() - startTime)
        }
      }
      
      // Get results
      val metrics = perfMonitor.getMetrics()
      println(s"""Load Test Results:
                 |Sustained Transfer Rate: ${metrics.dmaTransferRate} transfers/sec
                 |Data Throughput: ${metrics.dmaTransferRate * bufferSize * 2 / (1024*1024)} MB/s
                 |Average Latency: ${metrics.averageLatency} us
                 |Peak Latency: ${metrics.peakLatency} us
                 |Underruns: ${metrics.underruns}
                 |Overruns: ${metrics.overruns}
                 |""".stripMargin)
      
      // Verify results
      assert(metrics.underruns + metrics.overruns < 10, "Too many errors under load")
      assert(metrics.dmaTransferRate > 1000, "Transfer rate too low under load")
      assert(metrics.peakLatency < 2000, "Excessive latency under load")
    }
  }
}