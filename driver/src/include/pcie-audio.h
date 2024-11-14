#ifndef __PCIE_AUDIO_H
#define __PCIE_AUDIO_H

#include <linux/types.h>
#include <sound/core.h>
#include <sound/pcm.h>

#define DRIVER_NAME     "pcie-audio"
#define DRIVER_VERSION  "1.0.0"

/* Hardware capabilities */
#define MAX_CHANNELS        8
#define MAX_BUFFER_SIZE    (256 * 1024)  /* 256KB */
#define MIN_PERIOD_SIZE    1024
#define MAX_PERIOD_SIZE    (32 * 1024)   /* 32KB */
#define MIN_PERIODS        2
#define MAX_PERIODS        32
#define DMA_DESC_COUNT     32
#define FIFO_SIZE         1024
#define MAX_DSD_RATE      (44100 * 128)  /* DSD128 */

/* Register definitions */
#define REG_CTRL_FORMAT           0x000
#define REG_CTRL_SAMPLE_FAMILY    0x004
#define REG_CTRL_SAMPLE_MULTI     0x008
#define REG_CTRL_DSD_MODE         0x00C
#define REG_CTRL_CLOCK_SRC        0x010
#define REG_CTRL_MASTER_MODE      0x014
#define REG_CTRL_PB_ENABLE        0x018
#define REG_CTRL_CAP_ENABLE       0x01C
#define REG_CTRL_RESET            0x020

/* Advanced control registers */
#define REG_CTRL_MCLK_FREQ       0x030
#define REG_CTRL_TARGET_RATE     0x034
#define REG_CTRL_PB_THRESHOLD    0x038
#define REG_CTRL_CAP_THRESHOLD   0x03C
#define REG_CTRL_I2S_BITDEPTH    0x040
#define REG_CTRL_I2S_ALIGNMENT   0x044
#define REG_CTRL_I2S_TDM         0x048
#define REG_CTRL_I2S_TDM_SLOTS   0x04C
#define REG_CTRL_MCLK_DIV        0x050
#define REG_CTRL_BCLK_DIV        0x054
#define REG_CTRL_SYNC_TIMEOUT    0x058
#define REG_CTRL_AUTO_RATE       0x05C

/* DMA registers */
#define REG_DMA_PB_DESC_BASE     0x100
#define REG_DMA_PB_DESC_COUNT    0x108
#define REG_DMA_PB_CURRENT       0x10C
#define REG_DMA_PB_SIZE          0x110
#define REG_DMA_PB_IRQ_EN        0x114
#define REG_DMA_PB_THRESHOLD     0x118

#define REG_DMA_CAP_DESC_BASE    0x200
#define REG_DMA_CAP_DESC_COUNT   0x208
#define REG_DMA_CAP_CURRENT      0x20C
#define REG_DMA_CAP_SIZE         0x210
#define REG_DMA_CAP_IRQ_EN       0x214
#define REG_DMA_CAP_THRESHOLD    0x218

/* Status registers */
#define REG_STATUS_LOCKED        0x300
#define REG_STATUS_ACTUAL_RATE   0x304
#define REG_STATUS_CLOCK_SRC     0x308
#define REG_STATUS_PB_UNDERRUN   0x30C
#define REG_STATUS_CAP_OVERRUN   0x310
#define REG_STATUS_DMA_ERROR     0x314
#define REG_STATUS_FORMAT_ERROR  0x318

/* DMA descriptor structure */
struct pcie_audio_dma_desc {
    __le64 address;    /* Buffer physical address */
    __le32 length;     /* Buffer length in bytes */
    __le32 flags;      /* Control flags */
    __le64 next;       /* Next descriptor physical address */
} __packed;

/* DMA descriptor flags */
#define DESC_FLAG_INT      (1 << 0)    /* Generate interrupt */
#define DESC_FLAG_LAST     (1 << 1)    /* Last descriptor in chain */
#define DESC_FLAG_WRAP     (1 << 2)    /* Wrap to start of ring */
#define DESC_FLAG_OWNED    (1 << 31)   /* Owned by hardware */

/* Stream private data */
struct pcie_audio_stream {
    struct snd_pcm_substream *substream;
    struct pcie_audio_dma_desc *desc;
    dma_addr_t desc_dma;
    unsigned int desc_count;
    unsigned int current_desc;
    
    size_t period_size;
    size_t buffer_size;
    unsigned int periods;
    
    /* Performance monitoring */
    unsigned long interrupts;
    unsigned long errors;
    ktime_t last_interrupt;
    unsigned int latency;
    
    /* Buffer management */
    unsigned int hw_ptr;      /* Hardware pointer in frames */
    unsigned int prev_hw_ptr; /* Previous hardware pointer */
    
    /* Stream configuration */
    unsigned int channels;
    unsigned int rate;
    snd_pcm_format_t format;
    bool is_dsd;
};

/* Saved registers for power management */
struct pcie_audio_saved_regs {
    u32 ctrl_format;
    u32 ctrl_sample_family;
    u32 ctrl_master_mode;
    u32 dma_config;
    u32 clock_config;
    u32 threshold_config;
};

/* Main driver structure */
struct pcie_audio {
    struct pci_dev *pci;
    struct snd_card *card;
    struct snd_pcm *pcm;
    
    void __iomem *reg_base;
    
    struct pcie_audio_stream playback;
    struct pcie_audio_stream capture;
    
    /* Current configuration */
    unsigned int sample_rate;
    unsigned int channels;
    unsigned int format;
    bool is_dsd;
    
    /* Statistics */
    struct {
        unsigned long pb_underruns;
        unsigned long cap_overruns;
        unsigned long clock_unlocks;
        unsigned long dma_errors;
        ktime_t start_time;
    } stats;
    
    /* Power management */
    struct pcie_audio_saved_regs saved_registers;
    
    /* Spinlocks */
    spinlock_t reg_lock;    /* For register access */
    spinlock_t pb_lock;     /* For playback state */
    spinlock_t cap_lock;    /* For capture state */
};

/* Function prototypes */
int pcie_audio_create_controls(struct pcie_audio *chip);
int pcie_audio_proc_init(struct pcie_audio *chip);
void pcie_audio_proc_free(struct pcie_audio *chip);

#endif /* __PCIE_AUDIO_H */