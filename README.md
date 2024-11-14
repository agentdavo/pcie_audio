# PCIe Professional Audio Interface

## Overview
This project implements a professional-grade PCIe audio interface supporting 8-channel I2S and DSD audio with high-performance DMA capabilities. The implementation consists of a SpinalHDL hardware description and a complete Linux ALSA driver.

## Project Structure
```
pcie-audio/
├── build.sbt                 # SBT build configuration
├── project/
│   ├── build.properties     # SBT version specification
│   └── plugins.sbt          # SBT plugins configuration
├── hardware/                 # SpinalHDL hardware implementation
│   ├── src/
│   │   ├── main/scala/
│   │   │   └── audio/
│   │   │       ├── AudioPCIeTop.scala
│   │   │       ├── DMAEngine.scala
│   │   │       └── AudioProcessor.scala
│   │   └── test/scala/
│   │       └── audio/
│   │           └── HardwareSpec.scala
├── simulation/              # Simulation and testbench code
│   ├── src/
│   │   ├── main/scala/
│   │   │   └── audio/
│   │   │       ├── AudioTestBench.scala
│   │   │       └── SimulationHelpers.scala
│   │   └── test/scala/
│   │       └── audio/
│   │           ├── AudioPCIeTest.scala
│   │           └── PerformanceTest.scala
└── driver/                  # Linux ALSA driver
    ├── Makefile
    ├── pcie-audio.h
    └── src/
        ├── pcie-audio-main.c
        ├── pcie-audio-pcm.c
        └── pcie-audio-control.c
```

## Prerequisites

### Software Requirements
- JDK 8 or later
- SBT 1.9.7 or later
- Scala 2.12.16
- SpinalHDL 1.9.4
- Linux kernel headers (for driver)
- GHDL (for simulation)

### Hardware Requirements
- PCIe-capable development board
- Audio codec with I2S/DSD support
- Appropriate clock sources (44.1kHz/48kHz families)

## Building the Project

### Setting Up the Build Environment
```bash
# Install SBT (Debian/Ubuntu)
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
sudo apt-get update
sudo apt-get install sbt

# Install GHDL
sudo apt-get install ghdl
```

### Building Hardware Implementation
```bash
# Generate Verilog from SpinalHDL
sbt "hardware/runMain audio.AudioPCIeTop"

# Run hardware tests
sbt "hardware/test"

# Run specific test
sbt "hardware/testOnly audio.HardwareSpec"
```

### Running Simulations
```bash
# Run all simulations
sbt "simulation/test"

# Run specific simulation
sbt "simulation/runMain audio.AudioPCIeTest"

# Run performance tests
sbt "simulation/runMain audio.AudioPCIePerformanceTest"
```

### Building the Driver
```bash
cd driver
make
sudo make install
```

## Development Workflow

### Hardware Development
1. Modify SpinalHDL code in `hardware/src/main/scala/audio/`
2. Run tests: `sbt "hardware/test"`
3. Generate Verilog: `sbt "hardware/runMain audio.AudioPCIeTop"`
4. Simulate: `sbt "simulation/test"`

### Available SBT Commands
```bash
# Clean all projects
sbt clean

# Compile all projects
sbt compile

# Run all tests
sbt test

# Generate code coverage report
sbt coverage test
sbt coverageReport

# Format code
sbt scalafmtAll

# Create standalone JAR
sbt assembly
```

### Code Coverage
```bash
# Generate coverage report
sbt clean coverage test coverageReport

# View report in browser
open target/scala-2.12/scoverage-report/index.html
```

### Continuous Integration
The project includes configuration for:
- Code formatting (scalafmt)
- Code coverage (scoverage)
- Test automation
- Documentation generation

## Hardware Features

### Audio Capabilities
- 8 channels of I2S audio input/output
- Support for DSD audio (DSD64, DSD128)
- Sample rates up to 192kHz
- 24/32-bit audio support
- Dual clock domain support (44.1kHz and 48kHz families)

### PCIe Interface
- PCIe x1 configuration
- High-performance DMA engine
- MSI/MSI-X interrupt support
- Configurable buffer management

## Software Features

### Linux Driver
- Full ALSA driver integration
- Low-latency DMA engine
- Performance monitoring via sysfs
- Debug interface via procfs
- Power management support

### Testing and Debugging
- Comprehensive test suite
- Performance benchmarking
- Waveform generation
- Hardware simulation

## Performance Considerations

### DMA Configuration
- Configure DMA burst size based on PCIe capability
- Adjust buffer thresholds based on latency requirements
- Use MSI-X for best interrupt performance

### Buffer Management
- Minimum period size: 1024 frames
- Maximum period size: 32KB
- Recommended periods: 2-4 for low latency
- Buffer size up to 256KB supported

## Troubleshooting

### Common Issues
1. Clock Lock Failures
   - Check master clock presence
   - Verify clock configuration
   - Monitor lock status register

2. DMA Issues
   - Check descriptor configuration
   - Monitor buffer levels
   - Verify interrupt handling

3. Build Issues
   - Verify SBT version: `sbt sbtVersion`
   - Check Scala version: `sbt scalaVersion`
   - Ensure GHDL is installed: `ghdl --version`

## Contributing
1. Fork the repository
2. Create your feature branch
3. Run tests: `sbt test`
4. Format code: `sbt scalafmtAll`
5. Create pull request

## License
This project is licensed under the MIT License - see the LICENSE file for details.
