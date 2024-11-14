#include <linux/module.h>
#include <linux/delay.h>
#include "pcie-audio.h"

static inline u32 pcie_audio_read(struct pcie_audio *chip, unsigned int reg)
{
    u32 val;
    unsigned long flags;
    
    spin_lock_irqsave(&chip->reg_lock, flags);
    val = readl(chip->reg_base + reg);
    spin_unlock_irqrestore(&chip->reg_lock, flags);
    
    return val;
}

static inline void pcie_audio_write(struct pcie_audio *chip,
                                  unsigned int reg, u32 val)
{
    unsigned long flags;
    
    spin_lock_irqsave(&chip->reg_lock, flags);
    writel(val, chip->reg_base + reg);
    spin_unlock_irqrestore(&chip->reg_lock, flags);
}

int pcie_audio_init_hw(struct pcie_audio *chip)
{
    unsigned long timeout;
    u32 val;
    
    // Reset the hardware
    pcie_audio_write(chip, REG_CTRL_RESET, 1);
    msleep(1);  // Wait for reset to complete
    pcie_audio_write(chip, REG_CTRL_RESET, 0);
    msleep(1);  // Wait for hardware to stabilize
    
    // Configure DMA engine
    val = 0;
    val |= (512 << 16);           // Burst size
    val |= (1 << 8);             // Enable scatter-gather
    val |= (1 << 1);             // Enable completion interrupt
    val |= (1 << 0);             // Master enable
    pcie_audio_write(chip, REG_DMA_CONFIG, val);
    
    // Set default thresholds
    pcie_audio_write(chip, REG_DMA_PB_THRESHOLD, 1024);
    pcie_audio_write(chip, REG_DMA_CAP_THRESHOLD, 1024);
    
    // Configure default audio format
    val = 0;
    val |= (24 << 8);            // 24-bit depth
    val |= (8 - 1);              // 8 channels
    pcie_audio_write(chip, REG_CTRL_FORMAT, val);
    
    // Configure clock management
    val = 0;
    val |= (48000 << 16);        // Sync timeout (1ms at 48kHz)
    val |= (1 << 0);             // Enable auto rate detection
    pcie_audio_write(chip, REG_CTRL_SYNC_TIMEOUT, val);
    
    // Wait for clock lock
    timeout = jiffies + msecs_to_jiffies(1000);
    while (time_before(jiffies, timeout)) {
        if (pcie_audio_read(chip, REG_STATUS_LOCKED))
            goto clock_locked;
        msleep(1);
    }
    
    dev_warn(&chip->pci->dev, "Warning: Clock lock timeout\n");
    return 0;  // Continue anyway, clock might lock later
    
clock_locked:
    // Configure PCIe parameters
    val = 0;
    val |= (chip->pci->current_state << 24);  // Link speed
    val |= (512 << 16);                       // Max payload
    val |= (1 << 8);                          // Relaxed ordering
    val |= (1 << 0);                          // Enable features
    pcie_audio_write(chip, REG_PCIE_CONFIG, val);
    
    // Clear all status registers
    pcie_audio_write(chip, REG_STATUS_PB_UNDERRUN, 0xFFFFFFFF);
    pcie_audio_write(chip, REG_STATUS_CAP_OVERRUN, 0xFFFFFFFF);
    pcie_audio_write(chip, REG_STATUS_DMA_ERROR, 0xFFFFFFFF);
    
    return 0;
}

void pcie_audio_pcie_init(struct pcie_audio *chip)
{
    // Set optimal PCIe read request size
    pcie_set_readrq(chip->pci, 512);
    
    // Enable bus mastering
    pci_set_master(chip->pci);
    
    // Try MSI-X first, then MSI, then legacy interrupts
    if (pci_alloc_irq_vectors(chip->pci, 1, 8, PCI_IRQ_MSIX) < 0) {
        if (pci_alloc_irq_vectors(chip->pci, 1, 1, PCI_IRQ_MSI) < 0) {
            pci_alloc_irq_vectors(chip->pci, 1, 1, PCI_IRQ_LEGACY);
        }
    }
    
    // Configure PCIe link settings
    pcie_capability_clear_and_set_word(chip->pci, PCI_EXP_DEVCTL,
                                     PCI_EXP_DEVCTL_READRQ,
                                     0x5000);  // 512 bytes max read request
    
    pcie_capability_clear_and_set_word(chip->pci, PCI_EXP_DEVCTL,
                                     PCI_EXP_DEVCTL_PAYLOAD,
                                     0x2000);  // 512 bytes max payload
}