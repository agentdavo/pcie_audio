package audio

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

class PCIeConfigHandler extends Component {
  val io = new Bundle {
    val cfg = new Bundle {
      // PCIe configuration space access
      val addr = in UInt(12 bits)
      val write = in Bool()
      val writeData = in Bits(32 bits)
      val read = in Bool()
      val readData = out Bits(32 bits)
      
      // PCIe configuration interface
      val maxPayloadSize = out UInt(3 bits)
      val maxReadReqSize = out UInt(3 bits)
      val relaxedOrdering = out Bool()
      val noSnoop = out Bool()
      val maxTags = out UInt(8 bits)
      val msiEnable = out Bool()
      val msixEnable = out Bool()
      
      // Error reporting
      val correctable = out Bool()
      val uncorrectable = out Bool()
      val fatal = out Bool()
    }
    
    // AXI configuration interface
    val axi = slave(Axi4Config(
      addressWidth = 12,
      dataWidth = 32,
      idWidth = 4
    ))
  }
  
  // PCIe Configuration Registers
  val regs = new Area {
    // Device identification
    val deviceId = Reg(Bits(16 bits)) init(0x1234)  // Example device ID
    val vendorId = Reg(Bits(16 bits)) init(0x5678)  // Example vendor ID
    val revisionId = Reg(Bits(8 bits)) init(0x01)
    val subsystemId = Reg(Bits(16 bits)) init(0)
    val subsystemVendorId = Reg(Bits(16 bits)) init(0)
    
    // Command and status registers
    val command = Reg(Bits(16 bits)) init(0)
    val status = Reg(Bits(16 bits)) init(0)
    
    // PCIe capabilities
    val maxPayloadSize = Reg(UInt(3 bits)) init(0)  // 128 bytes
    val maxReadReqSize = Reg(UInt(3 bits)) init(0)  // 128 bytes
    val relaxedOrdering = Reg(Bool()) init(True)
    val noSnoop = Reg(Bool()) init(False)
    val maxTags = Reg(UInt(8 bits)) init(32)
    
    // MSI/MSI-X configuration
    val msiControl = Reg(Bits(16 bits)) init(0)
    val msiAddr = Reg(UInt(64 bits)) init(0)
    val msiData = Reg(Bits(16 bits)) init(0)
    val msixControl = Reg(Bits(16 bits)) init(0)
    
    // Error handling
    val corrErrorStatus = Reg(Bits(32 bits)) init(0)
    val uncorrErrorStatus = Reg(Bits(32 bits)) init(0)
    
    // Connect to outputs
    io.cfg.maxPayloadSize := maxPayloadSize
    io.cfg.maxReadReqSize := maxReadReqSize
    io.cfg.relaxedOrdering := relaxedOrdering
    io.cfg.noSnoop := noSnoop
    io.cfg.maxTags := maxTags
    io.cfg.msiEnable := msiControl(0)
    io.cfg.msixEnable := msixControl(0)
  }
  
  // Configuration space access
  val cfgAccess = new Area {
    // Read access
    when(io.cfg.read) {
      switch(io.cfg.addr) {
        is(0x00) { io.cfg.readData := regs.deviceId ## regs.vendorId }
        is(0x04) { io.cfg.readData := regs.status ## regs.command }
        is(0x08) { io.cfg.readData := U(0, 8 bits) ## regs.revisionId ## 
                                     B"000000" ## B"000001" ## B"0000" }  // Class code
        is(0x0C) { io.cfg.readData := 0 }  // Cache line, latency, header type
        is(0x2C) { io.cfg.readData := regs.subsystemId ## regs.subsystemVendorId }
        is(0x3C) { io.cfg.readData := 0 }  // Interrupt line/pin
        
        // PCIe capability
        is(0x70) { io.cfg.readData := regs.maxPayloadSize.asBits ## 
                                     regs.maxReadReqSize.asBits ##
                                     regs.relaxedOrdering.asBits ##
                                     regs.noSnoop.asBits ##
                                     regs.maxTags.asBits }
        
        // MSI capability
        is(0x80) { io.cfg.readData := regs.msiControl }
        is(0x84) { io.cfg.readData := regs.msiAddr(31 downto 0) }
        is(0x88) { io.cfg.readData := regs.msiAddr(63 downto 32) }
        is(0x8C) { io.cfg.readData := regs.msiData ## B(0, 16 bits) }
        
        // Error reporting
        is(0x100) { io.cfg.readData := regs.corrErrorStatus }
        is(0x104) { io.cfg.readData := regs.uncorrErrorStatus }
        
        default { io.cfg.readData := 0 }
      }
    }
    
    // Write access
    when(io.cfg.write) {
      switch(io.cfg.addr) {
        is(0x04) { 
          regs.command := io.cfg.writeData(15 downto 0)
          // Update bus master enable, memory space enable, etc.
        }
        
        is(0x70) {
          regs.maxPayloadSize := io.cfg.writeData(2 downto 0).asUInt
          regs.maxReadReqSize := io.cfg.writeData(5 downto 3).asUInt
          regs.relaxedOrdering := io.cfg.writeData(6)
          regs.noSnoop := io.cfg.writeData(7)
          regs.maxTags := io.cfg.writeData(15 downto 8).asUInt
        }
        
        is(0x80) { 
          regs.msiControl := io.cfg.writeData(15 downto 0)
          // Update MSI enable and multiple message capable
        }
        
        is(0x84) { regs.msiAddr(31 downto 0) := io.cfg.writeData }
        is(0x88) { regs.msiAddr(63 downto 32) := io.cfg.writeData }
        is(0x8C) { regs.msiData := io.cfg.writeData(15 downto 0) }
        
        is(0x100) { regs.corrErrorStatus := io.cfg.writeData }
        is(0x104) { regs.uncorrErrorStatus := io.cfg.writeData }
      }
    }
  }
  
  // Error handling
  val errorHandler = new Area {
    // Error detection flags
    val correctable = RegInit(False)
    val uncorrectable = RegInit(False)
    val fatal = RegInit(False)
    
    // Connect to outputs
    io.cfg.correctable := correctable
    io.cfg.uncorrectable := uncorrectable
    io.cfg.fatal := fatal
    
    // Error reporting logic
    when(correctable) {
      regs.corrErrorStatus := regs.corrErrorStatus | B"1"
    }
    
    when(uncorrectable) {
      regs.uncorrErrorStatus := regs.uncorrErrorStatus | B"1"
    }
    
    when(fatal) {
      regs.uncorrErrorStatus := regs.uncorrErrorStatus | B"10"
      regs.status := regs.status | B"10000"  // Set fatal error bit
    }
  }
  
  // Power management
  val powerMgmt = new Area {
    val pmControl = Reg(Bits(16 bits)) init(0)
    val pmStatus = Reg(Bits(16 bits)) init(0)
    
    // Power state transitions
    val currentState = RegInit(U"00")  // D0
    
    when(pmControl(1 downto 0) =/= currentState) {
      // Handle power state transition
      currentState := pmControl(1 downto 0)
      pmStatus(1 downto 0) := currentState
    }
  }
  
  // Link training and status
  val linkControl = new Area {
    val linkStatus = Reg(Bits(16 bits)) init(0)
    val linkControl = Reg(Bits(16 bits)) init(0)
    
    // Link width and speed negotiation
    val negotiatedWidth = RegInit(U"001")  // x1
    val negotiatedSpeed = RegInit(U"1")    // Gen1
    
    // Update link status
    linkStatus(3 downto 0) := negotiatedWidth
    linkStatus(7 downto 4) := negotiatedSpeed
  }
  
  // Debug and statistics
  val debug = new Area {
    val configAccessCount = Reg(UInt(32 bits)) init(0)
    val errorCount = Reg(UInt(16 bits)) init(0)
    
    when(io.cfg.read || io.cfg.write) {
      configAccessCount := configAccessCount + 1
    }
    
    when(errorHandler.correctable || errorHandler.uncorrectable) {
      errorCount := errorCount + 1
    }
  }
}