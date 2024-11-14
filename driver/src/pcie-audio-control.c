#include <linux/module.h>
#include <sound/control.h>
#include "pcie-audio.h"

// Mixer control getters/setters
static int master_mode_info(struct snd_kcontrol *kcontrol,
                          struct snd_ctl_elem_info *uinfo)
{
    uinfo->type = SNDRV_CTL_ELEM_TYPE_BOOLEAN;
    uinfo->count = 1;
    uinfo->value.integer.min = 0;
    uinfo->value.integer.max = 1;
    return 0;
}

static int master_mode_get(struct snd_kcontrol *kcontrol,
                          struct snd_ctl_elem_value *ucontrol)
{
    struct pcie_audio *chip = snd_kcontrol_chip(kcontrol);
    u32 val = pcie_audio_read(chip, kcontrol->private_value);
    ucontrol->value.integer.value[0] = val & 1;
    return 0;
}

static int master_mode_put(struct snd_kcontrol *kcontrol,
                          struct snd_ctl_elem_value *ucontrol)
{
    struct pcie_audio *chip = snd_kcontrol_chip(kcontrol);
    u32 val = ucontrol->value.integer.value[0] & 1;
    pcie_audio_write(chip, kcontrol->private_value, val);
    return 1;
}

static int clock_source_info(struct snd_kcontrol *kcontrol,
                           struct snd_ctl_elem_info *uinfo)
{
    static const char *texts[] = {"Auto", "44.1kHz", "48kHz"};
    uinfo->type = SNDRV_CTL_ELEM_TYPE_ENUMERATED;
    uinfo->count = 1;
    uinfo->value.enumerated.items = 3;
    if (uinfo->value.enumerated.item >= 3)
        uinfo->value.enumerated.item = 2;
    strcpy(uinfo->value.enumerated.name, texts[uinfo->value.enumerated.item]);
    return 0;
}

static int clock_source_get(struct snd_kcontrol *kcontrol,
                          struct snd_ctl_elem_value *ucontrol)
{
    struct pcie_audio *chip = snd_kcontrol_chip(kcontrol);
    u32 val = pcie_audio_read(chip, kcontrol->private_value);
    ucontrol->value.enumerated.item[0] = val & 3;
    return 0;
}

static int clock_source_put(struct snd_kcontrol *kcontrol,
                          struct snd_ctl_elem_value *ucontrol)
{
    struct pcie_audio *chip = snd_kcontrol_chip(kcontrol);
    u32 val = ucontrol->value.enumerated.item[0];
    if (val >= 3)
        return -EINVAL;
    pcie_audio_write(chip, kcontrol->private_value, val);
    return 1;
}

static int rate_info(struct snd_kcontrol *kcontrol,
                    struct snd_ctl_elem_info *uinfo)
{
    static const char *texts[] = {
        "44100", "48000", "88200", "96000", "176400", "192000"
    };
    uinfo->type = SNDRV_CTL_ELEM_TYPE_ENUMERATED;
    uinfo->count = 1;
    uinfo->value.enumerated.items = 6;
    if (uinfo->value.enumerated.item >= 6)
        uinfo->value.enumerated.item = 5;
    strcpy(uinfo->value.enumerated.name, texts[uinfo->value.enumerated.item]);
    return 0;
}

static int rate_get(struct snd_kcontrol *kcontrol,
                   struct snd_ctl_elem_value *ucontrol)
{
    struct pcie_audio *chip = snd_kcontrol_chip(kcontrol);
    u32 val = pcie_audio_read(chip, REG_CTRL_TARGET_RATE);
    
    switch (val) {
        case 44100:  ucontrol->value.enumerated.item[0] = 0; break;
        case 48000:  ucontrol->value.enumerated.item[0] = 1; break;
        case 88200:  ucontrol->value.enumerated.item[0] = 2; break;
        case 96000:  ucontrol->value.enumerated.item[0] = 3; break;
        case 176400: ucontrol->value.enumerated.item[0] = 4; break;
        case 192000: ucontrol->value.enumerated.item[0] = 5; break;
        default:     ucontrol->value.enumerated.item[0] = 1; break;
    }
    return 0;
}

static int rate_put(struct snd_kcontrol *kcontrol,
                   struct snd_ctl_elem_value *ucontrol)
{
    struct pcie_audio *chip = snd_kcontrol_chip(kcontrol);
    static const unsigned int rates[] = {
        44100, 48000, 88200, 96000, 176400, 192000
    };
    u32 val = ucontrol->value.enumerated.item[0];
    
    if (val >= 6)
        return -EINVAL;
    
    pcie_audio_write(chip, REG_CTRL_TARGET_RATE, rates[val]);
    return 1;
}

static int format_info(struct snd_kcontrol *kcontrol,
                      struct snd_ctl_elem_info *uinfo)
{
    static const char *texts[] = {"I2S", "DSD"};
    uinfo->type = SNDRV_CTL_ELEM_TYPE_ENUMERATED;
    uinfo->count = 1;
    uinfo->value.enumerated.items = 2;
    if (uinfo->value.enumerated.item >= 2)
        uinfo->value.enumerated.item = 0;
    strcpy(uinfo->value.enumerated.name, texts[uinfo->value.enumerated.item]);
    return 0;
}

static int format_get(struct snd_kcontrol *kcontrol,
                     struct snd_ctl_elem_value *ucontrol)
{
    struct pcie_audio *chip = snd_kcontrol_chip(kcontrol);
    u32 val = pcie_audio_read(chip, REG_CTRL_FORMAT);
    ucontrol->value.enumerated.item[0] = (val >> 31) & 1;
    return 0;
}

static int format_put(struct snd_kcontrol *kcontrol,
                     struct snd_ctl_elem_value *ucontrol)
{
    struct pcie_audio *chip = snd_kcontrol_chip(kcontrol);
    u32 val = pcie_audio_read(chip, REG_CTRL_FORMAT);
    val &= ~(1 << 31);
    val |= (ucontrol->value.enumerated.item[0] & 1) << 31;
    pcie_audio_write(chip, REG_CTRL_FORMAT, val);
    return 1;
}

// Create control elements
int pcie_audio_create_controls(struct pcie_audio *chip)
{
    static struct snd_kcontrol_new controls[] = {
        {
            .iface = SNDRV_CTL_ELEM_IFACE_MIXER,
            .name = "Master Mode",
            .info = master_mode_info,
            .get = master_mode_get,
            .put = master_mode_put,
            .private_value = REG_CTRL_MASTER_MODE,
        },
        {
            .iface = SNDRV_CTL_ELEM_IFACE_MIXER,
            .name = "Clock Source",
            .info = clock_source_info,
            .get = clock_source_get,
            .put = clock_source_put,
            .private_value = REG_CTRL_CLOCK_SRC,
        },
        {
            .iface = SNDRV_CTL_ELEM_IFACE_MIXER,
            .name = "Sample Rate",
            .info = rate_info,
            .get = rate_get,
            .put = rate_put,
        },
        {
            .iface = SNDRV_CTL_ELEM_IFACE_MIXER,
            .name = "Format",
            .info = format_info,
            .get = format_get,
            .put = format_put,
        },
    };
    
    int err, i;
    
    for (i = 0; i < ARRAY_SIZE(controls); i++) {
        err = snd_ctl_add(chip->card, 
                         snd_ctl_new1(&controls[i], chip));
        if (err < 0)
            return err;
    }
    
    return 0;
}