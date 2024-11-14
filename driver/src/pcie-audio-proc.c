#include <linux/module.h>
#include <sound/info.h>
#include <sound/pcm.h>
#include "pcie-audio.h"

static void pcie_audio_proc_read(struct snd_info_entry *entry,
                                struct snd_info_buffer *buffer)
{
    struct pcie_audio *chip = entry->private_data;
    unsigned int status;
    
    // Hardware Status
    snd_iprintf(buffer, "PCIe Audio Interface Status\n\n");
    status = pcie_audio_read(chip, REG_STATUS_LOCKED);
    snd_iprintf(buffer, "Clock Lock: %s\n", status ? "Yes" : "No");
    
    status = pcie_audio_read(chip, REG_STATUS_ACTUAL_RATE);
    snd_iprintf(buffer, "Sample Rate: %u Hz\n", status);
    
    status = pcie_audio_read(chip, REG_STATUS_MCLK_VALID);
    snd_iprintf(buffer, "MCLK Status: %s\n", status ? "Valid" : "Invalid");
    
    // DMA Status
    snd_iprintf(buffer, "\nDMA Status:\n");
    
    // Playback
    status = pcie_audio_read(chip, REG_STATUS_PB_DESC_ACTIVE);
    snd_iprintf(buffer, "Playback:\n");
    snd_iprintf(buffer, "  Active Descriptors: %u\n", status);
    snd_iprintf(buffer, "  Total Bytes: %u\n",
                pcie_audio_read(chip, REG_STATUS_PB_BYTES_PROC));
    snd_iprintf(buffer, "  Underruns: %lu\n", chip->stats.pb_underruns);
    
    if (chip->playback.substream) {
        struct snd_pcm_runtime *runtime = chip->playback.substream->runtime;
        snd_iprintf(buffer, "  Buffer Size: %lu bytes\n", runtime->dma_bytes);
        snd_iprintf(buffer, "  Period Size: %lu bytes\n", 
                   frames_to_bytes(runtime, runtime->period_size));
        snd_iprintf(buffer, "  Avg Latency: %u us\n", chip->playback.latency);
    }
    
    // Capture
    status = pcie_audio_read(chip, REG_STATUS_CAP_DESC_ACTIVE);
    snd_iprintf(buffer, "\nCapture:\n");
    snd_iprintf(buffer, "  Active Descriptors: %u\n", status);
    snd_iprintf(buffer, "  Total Bytes: %u\n",
                pcie_audio_read(chip, REG_STATUS_CAP_BYTES_PROC));
    snd_iprintf(buffer, "  Overruns: %lu\n", chip->stats.cap_overruns);
    
    if (chip->capture.substream) {
        struct snd_pcm_runtime *runtime = chip->capture.substream->runtime;
        snd_iprintf(buffer, "  Buffer Size: %lu bytes\n", runtime->dma_bytes);
        snd_iprintf(buffer, "  Period Size: %lu bytes\n",
                   frames_to_bytes(runtime, runtime->period_size));
        snd_iprintf(buffer, "  Avg Latency: %u us\n", chip->capture.latency);
    }
    
    // Error Statistics
    snd_iprintf(buffer, "\nError Statistics:\n");
    snd_iprintf(buffer, "Clock Unlocks: %lu\n", chip->stats.clock_unlocks);
    snd_iprintf(buffer, "DMA Errors: %lu\n", chip->stats.dma_errors);
    
    // Format and Clock Settings
    snd_iprintf(buffer, "\nCurrent Settings:\n");
    status = pcie_audio_read(chip, REG_CTRL_FORMAT);
    snd_iprintf(buffer, "Format: %s\n", (status >> 31) ? "DSD" : "I2S");
    snd_iprintf(buffer, "Bit Depth: %u\n", (status >> 8) & 0xFF);
    
    status = pcie_audio_read(chip, REG_CTRL_MASTER_MODE);
    snd_iprintf(buffer, "Clock Mode: %s\n", status ? "Master" : "Slave");
    
    status = pcie_audio_read(chip, REG_CTRL_CLOCK_SRC);
    snd_iprintf(buffer, "Clock Source: %s\n",
                status == 0 ? "Auto" :
                status == 1 ? "44.1kHz" : "48kHz");
}

void pcie_audio_proc_init(struct pcie_audio *chip)
{
    struct snd_info_entry *entry;
    
    if (!snd_card_proc_new(chip->card, "pcie-audio", &entry))
        snd_info_set_text_ops(entry, chip, pcie_audio_proc_read);
}

void pcie_audio_proc_free(struct pcie_audio *chip)
{
    snd_card_proc_free(chip->card);
}