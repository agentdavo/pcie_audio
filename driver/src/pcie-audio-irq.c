#include <linux/interrupt.h>
#include "pcie-audio.h"

static irqreturn_t pcie_audio_interrupt(int irq, void *dev_id)
{
    struct pcie_audio *chip = dev_id;
    unsigned int status;
    unsigned long flags;
    ktime_t now = ktime_get();
    
    // Read and clear interrupt status
    status = pcie_audio_read(chip, REG_STATUS_PB_UNDERRUN) |
             (pcie_audio_read(chip, REG_STATUS_CAP_OVERRUN) << 8) |
             (pcie_audio_read(chip, REG_STATUS_DMA_ERROR) << 16);
    
    if (!status)
        return IRQ_NONE;
    
    // Handle playback interrupts
    if (status & 0xFF) {
        spin_lock_irqsave(&chip->pb_lock, flags);
        
        if (chip->playback.substream) {
            chip->playback.interrupts++;
            chip->playback.latency = ktime_to_us(ktime_sub(now,
                                               chip->playback.last_interrupt));
            chip->playback.last_interrupt = now;
            
            if (status & (1 << 0)) {
                chip->stats.pb_underruns++;
                chip->playback.errors++;
                snd_pcm_stop_xrun(chip->playback.substream);
            } else {
                snd_pcm_period_elapsed(chip->playback.substream);
            }
        }
        
        spin_unlock_irqrestore(&chip->pb_lock, flags);
    }
    
    // Handle capture interrupts
    if (status & 0xFF00) {
        spin_lock_irqsave(&chip->cap_lock, flags);
        
        if (chip->capture.substream) {
            chip->capture.interrupts++;
            chip->capture.latency = ktime_to_us(ktime_sub(now,
                                              chip->capture.last_interrupt));
            chip->capture.last_interrupt = now;
            
            if (status & (1 << 8)) {
                chip->stats.cap_overruns++;
                chip->capture.errors++;
                snd_pcm_stop_xrun(chip->capture.substream);
            } else {
                snd_pcm_period_elapsed(chip->capture.substream);
            }
        }
        
        spin_unlock_irqrestore(&chip->cap_lock, flags);
    }
    
    // Handle DMA errors
    if (status & 0xFF0000) {
        chip->stats.dma_errors++;
        
        // Reset DMA engine
        pcie_audio_write(chip, REG_CTRL_PB_ENABLE, 0);
        pcie_audio_write(chip, REG_CTRL_CAP_ENABLE, 0);
        
        // Re-initialize DMA
        pcie_audio_write(chip, REG_DMA_CONFIG, 
            (512 << 16) |        // Burst size
            (1 << 8) |          // Enable scatter-gather
            (1 << 1) |          // Enable completion interrupt
            (1 << 0));          // Master enable
    }
    
    // Clear interrupt status
    pcie_audio_write(chip, REG_STATUS_PB_UNDERRUN, 0xFFFFFFFF);
    pcie_audio_write(chip, REG_STATUS_CAP_OVERRUN, 0xFFFFFFFF);
    pcie_audio_write(chip, REG_STATUS_DMA_ERROR, 0xFFFFFFFF);
    
    return IRQ_HANDLED;
}

int pcie_audio_setup_irq(struct pcie_audio *chip)
{
    int err;
    
    // First try MSI-X
    err = pci_alloc_irq_vectors(chip->pci, 1, 8,
                               PCI_IRQ_MSIX | PCI_IRQ_AFFINITY);
    if (err < 0) {
        // Fall back to MSI
        err = pci_alloc_irq_vectors(chip->pci, 1, 1, PCI_IRQ_MSI);
        if (err < 0) {
            // Finally try legacy interrupts
            err = pci_alloc_irq_vectors(chip->pci, 1, 1, PCI_IRQ_LEGACY);
            if (err < 0)
                return err;
        }
    }
    
    err = request_irq(pci_irq_vector(chip->pci, 0),
                     pcie_audio_interrupt,
                     chip->pci->irq ? IRQF_SHARED : 0,
                     DRIVER_NAME,
                     chip);
    if (err < 0) {
        pci_free_irq_vectors(chip->pci);
        return err;
    }
    
    // Enable PCIe interrupts
    pcie_audio_write(chip, REG_DMA_PB_IRQ_EN, 0);  // Will be enabled during playback
    pcie_audio_write(chip, REG_DMA_CAP_IRQ_EN, 0); // Will be enabled during capture
    
    return 0;
}

void pcie_audio_free_irq(struct pcie_audio *chip)
{
    // Disable all interrupts
    pcie_audio_write(chip, REG_DMA_PB_IRQ_EN, 0);
    pcie_audio_write(chip, REG_DMA_CAP_IRQ_EN, 0);
    
    free_irq(pci_irq_vector(chip->pci, 0), chip);
    pci_free_irq_vectors(chip->pci);
}