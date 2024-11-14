package audio

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spinal.core.sim._
import spinal.core._
import spinal.lib._

class HardwareSpec extends AnyFlatSpec with Matchers {
  
  "AudioPCIeTop" should "compile without errors" in {
    val compiled = SimConfig
      .withWave
      .compile {
        val dut = new AudioPCIeTop(
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
        )
        dut
      }
    compiled should not be null
  }
  
  "DMAEngine" should "handle transfers correctly" in {
    SimConfig.withWave.compile(new DMAEngine(
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
      ),
      PCIeConfig(
        maxReadRequestSize = 512,
        maxPayloadSize = 256,
        completionTimeout = 0xA,
        relaxedOrdering = true,
        extendedTags = true,
        maxTags = 32
      )
    )).doSim { dut =>
      // DMA transfer test scenario
      dut.clockDomain.forkStimulus(10)
      
      var transferComplete = false
      
      // Monitor DMA completion
      fork {
        while(!transferComplete) {
          dut.clockDomain.waitSampling()
          if(dut.io.control.pbComplete.toBoolean) {
            transferComplete = true
          }
        }
      }
      
      // Setup and start transfer
      dut.clockDomain.waitSampling()
      dut.io.control.pbEnable #= true
      dut.io.control.pbDescBaseAddr #= 0x1000
      dut.io.control.pbDescCount #= 4
      
      // Wait for completion or timeout
      var timeout = 0
      while(!transferComplete && timeout < 1000) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      
      assert(transferComplete, "DMA transfer did not complete")
      assert(timeout < 1000, "DMA transfer timed out")
    }
  }
  
  "AudioProcessor" should "generate correct I2S timing" in {
    SimConfig.withWave.compile(new AudioProcessor(
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
    )).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      
      // Configure for I2S mode
      dut.io.format #= AudioFormat.I2S
      dut.io.sampleRateFamily #= SampleRateFamily.SF_44K1
      dut.io.masterMode #= true
      
      // Check I2S timing relationships
      var lastWs = false
      var wsToggleCount = 0
      var sckToggleCount = 0
      
      for(_ <- 0 until 1000) {
        dut.clockDomain.waitSampling()
        
        // Count SCK toggles
        if(dut.io.i2s.sck.toBoolean != dut.io.i2s.sck.previousValue.toBoolean) {
          sckToggleCount += 1
        }
        
        // Count WS toggles
        if(dut.io.i2s.ws.toBoolean != lastWs) {
          wsToggleCount += 1
          lastWs = dut.io.i2s.ws.toBoolean
        }
      }
      
      // Verify I2S timing relationships
      assert(sckToggleCount > wsToggleCount * 32, "Incorrect I2S timing ratio")
    }
  }
}