#include <linux/module.h>
#include <linux/pci.h>
#include <sound/pcm.h>
#include <sound/pcm_params.h>
#include "pcie-audio.h"

static int setup_dma_descriptors(struct pcie_audio *chip,
                               struct pcie_audio_stream *stream)
{
    struct snd_pcm_runtime *runtime = stream->substream->runtime;
    struct pcie_audio_dma_desc *desc;
    dma_addr_t buf = runtime->dma_addr;
    size_t period_bytes = frames_to_bytes(runtime, runtime->period_size);
    unsigned int i;
    
    // Free existing descriptors if any
    if (stream->desc) {
        dma_free_coherent(&chip->pci->dev,
                         stream->desc_count * sizeof(struct pcie_audio_dma_desc),
                         stream->desc, stream->desc_dma);
    }
    
    // Allocate descriptor ring
    stream->desc_count = DMA_DESC_COUNT;
    stream->desc = dma_alloc_coherent(&chip->pci->dev,
                                     stream->desc_count * sizeof(struct pcie_audio_dma_desc),
                                     &stream->desc_dma,
                                     GFP_KERNEL);
    if (!stream->desc)
        return -ENOMEM;
    
    // Initialize descriptors
    for (i = 0; i < stream->desc_count; i++) {
        desc = &stream->desc[i];
        desc->address = buf + (i * period_bytes);
        desc->length = period_bytes;
        desc->flags = (i % 2 == 1) ? DESC_FLAG_INT : 0;  // Interrupt on every other descriptor
        desc->next = stream->desc_dma +
                    ((i + 1) % stream->desc_count) * sizeof(struct pcie_audio_dma_desc);
        
        if (i == stream->desc_count - 1)
            desc->flags |= DESC_FLAG_WRAP;
    }
    
    stream->period_size = period_bytes;
    stream->buffer_size = runtime->dma_bytes;
    stream->periods = runtime->periods;
    stream->current_desc = 0;
    
    return 0;
}

static int pcie_audio_pcm_open(struct snd_pcm_substream *substream)
{
    struct pcie_audio *chip = snd_pcm_substream_chip(substream);
    struct pcie_audio_stream *stream;
    
    if (substream->stream == SNDRV_PCM_STREAM_PLAYBACK)
        stream = &chip->playback;
    else
        stream = &chip->capture;
    
    stream->substream = substream;
    substream->runtime->hw = pcie_audio_hw;
    
    stream->last_interrupt = ktime_get();
    stream->interrupts = 0;
    stream->errors = 0;
    stream->latency = 0;
    
    return 0;
}

static int pcie_audio_pcm_close(struct snd_pcm_substream *substream)
{
    struct pcie_audio *chip = snd_pcm_substream_chip(substream);
    struct pcie_audio_stream *stream;
    
    if (substream->stream == SNDRV_PCM_STREAM_PLAYBACK)
        stream = &chip->playback;
    else
        stream = &chip->capture;
    
    if (stream->desc) {
        dma_free_coherent(&chip->pci->dev,
                         stream->desc_count * sizeof(struct pcie_audio_dma_desc),
                         stream->desc, stream->desc_dma);
        stream->desc = NULL;
    }
    
    stream->substream = NULL;
    return 0;
}

static int pcie_audio_hw_params(struct snd_pcm_substream *substream,
                              struct snd_pcm_hw_params *params)
{
    struct pcie_audio *chip = snd_pcm_substream_chip(substream);
    struct pcie_audio_stream *stream;
    int err;
    
    if (substream->stream == SNDRV_PCM_STREAM_PLAYBACK)
        stream = &chip->playback;
    else
        stream = &chip->capture;
    
    // Allocate DMA buffer
    err = snd_pcm_lib_malloc_pages(substream, params_buffer_bytes(params));
    if (err < 0)
        return err;
    
    // Setup DMA descriptors
    err = setup_dma_descriptors(chip, stream);
    if (err < 0) {
        snd_pcm_lib_free_pages(substream);
        return err;
    }
    
    // Configure hardware parameters
    stream->channels = params_channels(params);
    stream->rate = params_rate(params);
    stream->format = params_format(params);
    
    // Setup hardware registers
    if (substream->stream == SNDRV_PCM_STREAM_PLAYBACK) {
        pcie_audio_write(chip, REG_DMA_PB_DESC_BASE, stream->desc_dma);
        pcie_audio_write(chip, REG_DMA_PB_DESC_COUNT, stream->desc_count);
        pcie_audio_write(chip, REG_DMA_PB_SIZE, stream->period_size);
        pcie_audio_write(chip, REG_DMA_PB_THRESHOLD, stream->period_size / 2);
    } else {
        pcie_audio_write(chip, REG_DMA_CAP_DESC_BASE, stream->desc_dma);
        pcie_audio_write(chip, REG_DMA_CAP_DESC_COUNT, stream->desc_count);
        pcie_audio_write(chip, REG_DMA_CAP_SIZE, stream->period_size);
        pcie_audio_write(chip, REG_DMA_CAP_THRESHOLD, stream->period_size / 2);
    }
    
    // Configure format
    u32 format_ctrl = 0;
    format_ctrl |= params_physical_width(params) << 8;
    format_ctrl |= stream->channels - 1;
    pcie_audio_write(chip, REG_CTRL_FORMAT, format_ctrl);
    
    // Configure sample rate
    u32 rate_ctrl = (stream->rate % 44100 == 0) ? 0 : (1 << 31);
    u32 base_rate = rate_ctrl ? 48000 : 44100;
    u32 multi = stream->rate / base_rate;
    rate_ctrl |= (multi - 1) << 8;
    
    pcie_audio_write(chip, REG_CTRL_SAMPLE_FAMILY, rate_ctrl);
    pcie_audio_write(chip, REG_CTRL_TARGET_RATE, stream->rate);
    
    return 0;
}

static int pcie_audio_hw_free(struct snd_pcm_substream *substream)
{
    return snd_pcm_lib_free_pages(substream);
}

static int pcie_audio_prepare(struct snd_pcm_substream *substream)
{
    struct pcie_audio *chip = snd_pcm_substream_chip(substream);
    struct pcie_audio_stream *stream;
    
    if (substream->stream == SNDRV_PCM_STREAM_PLAYBACK)
        stream = &chip->playback;
    else
        stream = &chip->capture;
    
    stream->current_desc = 0;
    stream->hw_ptr = 0;
    stream->prev_hw_ptr = 0;
    
    // Clear status and reset DMA
    if (substream->stream == SNDRV_PCM_STREAM_PLAYBACK) {
        pcie_audio_write(chip, REG_CTRL_PB_ENABLE, 0);
        pcie_audio_write(chip, REG_DMA_PB_IRQ_EN, 0);
        pcie_audio_write(chip, REG_STATUS_PB_UNDERRUN, 0xFFFFFFFF);
    } else {
        pcie_audio_write(chip, REG_CTRL_CAP_ENABLE, 0);
        pcie_audio_write(chip, REG_DMA_CAP_IRQ_EN, 0);
        pcie_audio_write(chip, REG_STATUS_CAP_OVERRUN, 0xFFFFFFFF);
    }
    
    return 0;
}

static int pcie_audio_trigger(struct snd_pcm_substream *substream, int cmd)
{
    struct pcie_audio *chip = snd_pcm_substream_chip(substream);
    struct pcie_audio_stream *stream;
    unsigned long flags;
    
    if (substream->stream == SNDRV_PCM_STREAM_PLAYBACK)
        stream = &chip->playback;
    else
        stream = &chip->capture;
    
    spin_lock_irqsave(substream->stream == SNDRV_PCM_STREAM_PLAYBACK ?
                      &chip->pb_lock : &chip->cap_lock, flags);
    
    switch (cmd) {
        case SNDRV_PCM_TRIGGER_START:
        case SNDRV_PCM_TRIGGER_RESUME:
            if (substream->stream == SNDRV_PCM_STREAM_PLAYBACK) {
                pcie_audio_write(chip, REG_DMA_PB_IRQ_EN, 1);
                pcie_audio_write(chip, REG_CTRL_PB_ENABLE, 1);
            } else {
                pcie_audio_write(chip, REG_DMA_CAP_IRQ_EN, 1);
                pcie_audio_write(chip, REG_CTRL_CAP_ENABLE, 1);
            }
            stream->last_interrupt = ktime_get();
            break;
            
        case SNDRV_PCM_TRIGGER_STOP:
        case SNDRV_PCM_TRIGGER_SUSPEND:
            if (substream->stream == SNDRV_PCM_STREAM_PLAYBACK) {
                pcie_audio_write(chip, REG_CTRL_PB_ENABLE, 0);
                pcie_audio_write(chip, REG_DMA_PB_IRQ_EN, 0);
            } else {
                pcie_audio_write(chip, REG_CTRL_CAP_ENABLE, 0);
                pcie_audio_write(chip, REG_DMA_CAP_IRQ_EN, 0);
            }
            break;
            
        default:
            spin_unlock_irqrestore(substream->stream == SNDRV_PCM_STREAM_PLAYBACK ?
                                 &chip->pb_lock : &chip->cap_lock, flags);
            return -EINVAL;
    }
    
    spin_unlock_irqrestore(substream->stream == SNDRV_PCM_STREAM_PLAYBACK ?
                          &chip->pb_lock : &chip->cap_lock, flags);
    return 0;
}

static snd_pcm_uframes_t pcie_audio_pointer(struct snd_pcm_substream *substream)
{
    struct pcie_audio *chip = snd_pcm_substream_chip(substream);
    struct pcie_audio_stream *stream;
    unsigned int current_desc, offset;
    snd_pcm_uframes_t frames;
    
    if (substream->stream == SNDRV_PCM_STREAM_PLAYBACK) {
        stream = &chip->playback;
        current_desc = pcie_audio_read(chip, REG_DMA_PB_CURRENT);
        offset = pcie_audio_read(chip, REG_STATUS_PB_DESC_ACTIVE);
    } else {
        stream = &chip->capture;
        current_desc = pcie_audio_read(chip, REG_DMA_CAP_CURRENT);
        offset = pcie_audio_read(chip, REG_STATUS_CAP_DESC_ACTIVE);
    }
    
    frames = (current_desc * stream->period_size + offset) /
             frames_to_bytes(substream->runtime, 1);
    
    return frames;
}

const struct snd_pcm_ops pcie_audio_pcm_ops = {
    .open = pcie_audio_pcm_open,
    .close = pcie_audio_pcm_close,
    .ioctl = snd_pcm_lib_ioctl,
    .hw_params = pcie_audio_hw_params,
    .hw_free = pcie_audio_hw_free,
    .prepare = pcie_audio_prepare,
    .trigger = pcie_audio_trigger,
    .pointer = pcie_audio_pointer,
};