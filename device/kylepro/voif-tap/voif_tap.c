// SPDX-License-Identifier: GPL-2.0
/*
 * Read-only Broadcom Hawaii VOIF downlink tap for the Samsung GT-S7580.
 *
 * The LineageOS kernel contains the Broadcom VOIF DSP implementation but was
 * built with VoIF_init() stubbed out.  This module opens that existing driver,
 * installs a callback which does not modify either DSP buffer, and publishes
 * the cellular downlink PCM through /dev/voif_tap.
 */
#include <linux/fs.h>
#include <linux/init.h>
#include <linux/kernel.h>
#include <linux/miscdevice.h>
#include <linux/module.h>
#include <linux/poll.h>
#include <linux/sched.h>
#include <linux/slab.h>
#include <linux/spinlock.h>
#include <linux/uaccess.h>
#include <linux/wait.h>

#define TAP_BYTES (64 * 1024)
#define INJECT_BYTES 4096 /* power of two: no compiler division helper */
#define AUDIO_DRIVER_VOIF 9
#define AUDIO_DRIVER_START 0
#define AUDIO_DRIVER_STOP 1
#define AUDIO_DRIVER_SET_VOIF_CB 15

typedef void *(*audio_open_fn)(int type);
typedef void (*audio_ctrl_fn)(void *handle, int command, void *argument);
typedef void (*audio_close_fn)(void *handle);
typedef unsigned long (*lookup_fn)(const char *name);

static unsigned char *tap_buffer;
static unsigned int tap_read_pos;
static unsigned int tap_write_pos;
static unsigned int tap_used;
static unsigned int tap_rate;
static unsigned long tap_frames;
static unsigned long tap_overruns;
static unsigned char inject_buffer[INJECT_BYTES];
static unsigned char inject_scratch[INJECT_BYTES];
static unsigned int inject_read_pos;
static unsigned int inject_write_pos;
static unsigned int inject_used;
static unsigned int inject_writers;
static spinlock_t tap_lock;
static DECLARE_WAIT_QUEUE_HEAD(tap_wait);

static audio_open_fn audio_open;
static audio_ctrl_fn audio_ctrl;
static audio_close_fn audio_close;
static void *voif_handle;
static bool voif_active;

typedef void (*voif_status_cb)(unsigned short buffer_index,
                               unsigned short sampling_rate);
typedef void (*register_voif_fn)(voif_status_cb callback);
typedef short *(*get_ul_fn)(unsigned short samples, unsigned short index,
                            unsigned char mode);
typedef short *(*get_dl_fn)(unsigned short samples, unsigned short index);
typedef void (*voif_control_fn)(unsigned short mode);

static register_voif_fn register_voif;
static get_ul_fn get_ul_buffer;
static get_dl_fn get_dl_buffer;
static voif_control_fn voif_control;
static voif_status_cb original_voif_handler;
static bool direct_duplex;
static unsigned long (*copy_from_user_fn)(void *, const void __user *,
                                          unsigned long);
static void *(*memset_fn)(void *, int, size_t);

extern unsigned long kallsyms_lookup_name(const char *name);

static int tap_open(struct inode *inode, struct file *file)
{
    unsigned long flags;

    /* A VOIF callback can run while no SIP call is reading.  Never replay
     * that stale cellular audio into the next call. */
    spin_lock_irqsave(&tap_lock, flags);
    tap_read_pos = tap_write_pos;
    tap_used = 0;
    if (file->f_mode & FMODE_WRITE) {
        inject_read_pos = 0;
        inject_write_pos = 0;
        inject_used = 0;
        inject_writers++;
    }
    spin_unlock_irqrestore(&tap_lock, flags);
    return 0;
}

static int tap_release(struct inode *inode, struct file *file)
{
    unsigned long flags;

    spin_lock_irqsave(&tap_lock, flags);
    if ((file->f_mode & FMODE_WRITE) && inject_writers)
        inject_writers--;
    if (!inject_writers) {
        inject_read_pos = 0;
        inject_write_pos = 0;
        inject_used = 0;
    }
    spin_unlock_irqrestore(&tap_lock, flags);
    return 0;
}

static void voif_callback(short *ul_data, short *dl_data,
                          unsigned int sample_count, unsigned char is_call_16k)
{
    unsigned long flags;
    unsigned int bytes = sample_count * sizeof(short);
    unsigned int first;

    (void)ul_data;
    if (!dl_data || !bytes || bytes > TAP_BYTES)
        return;

    spin_lock_irqsave(&tap_lock, flags);
    tap_rate = is_call_16k ? 16000 : 8000;
    while (TAP_BYTES - tap_used < bytes) {
        unsigned int drop = bytes - (TAP_BYTES - tap_used);
        if (drop > tap_used)
            drop = tap_used;
        tap_read_pos = (tap_read_pos + drop) % TAP_BYTES;
        tap_used -= drop;
        tap_overruns++;
    }
    first = min(bytes, TAP_BYTES - tap_write_pos);
    memcpy(tap_buffer + tap_write_pos, dl_data, first);
    if (first < bytes)
        memcpy(tap_buffer, (unsigned char *)dl_data + first, bytes - first);
    tap_write_pos = (tap_write_pos + bytes) % TAP_BYTES;
    tap_used += bytes;
    tap_frames++;

    /* Enhanced VOIF mode makes the current uplink ping-pong buffer writable.
     * While the app has the device open for writing, replace the handset mic
     * with one exact 20 ms SIP frame.  An underrun is digital silence, never
     * a fall-through to the physical microphone. */
    if (inject_writers && ul_data) {
        if (inject_used >= bytes) {
            first = min(bytes, INJECT_BYTES - inject_read_pos);
            memcpy(ul_data, inject_buffer + inject_read_pos, first);
            if (first < bytes)
                memcpy((unsigned char *)ul_data + first, inject_buffer,
                       bytes - first);
            inject_read_pos = (inject_read_pos + bytes) & (INJECT_BYTES - 1);
            inject_used -= bytes;
        } else {
            memset_fn(ul_data, 0, bytes);
            inject_read_pos = inject_write_pos;
            inject_used = 0;
        }
    }
    spin_unlock_irqrestore(&tap_lock, flags);
    wake_up_interruptible(&tap_wait);
}

static void direct_voif_callback(unsigned short buffer_index,
                                 unsigned short sampling_rate)
{
    unsigned short samples = sampling_rate ? 320 : 160;
    unsigned short index = buffer_index & 1;
    short *ul_data = get_ul_buffer(samples, index, 3);
    short *dl_data = get_dl_buffer(samples, index);

    voif_callback(ul_data, dl_data, samples, sampling_rate ? 1 : 0);
}

static ssize_t tap_read(struct file *file, char __user *user,
                        size_t count, loff_t *offset)
{
    unsigned long flags;
    unsigned int available;
    unsigned int first;
    int rc;

    (void)offset;
    if (!count)
        return 0;
    rc = wait_event_interruptible(tap_wait, tap_used || !voif_active);
    if (rc)
        return rc;
    if (!voif_active)
        return 0;

    spin_lock_irqsave(&tap_lock, flags);
    available = min_t(unsigned int, count, tap_used);
    first = min(available, TAP_BYTES - tap_read_pos);
    spin_unlock_irqrestore(&tap_lock, flags);

    if (copy_to_user(user, tap_buffer + tap_read_pos, first))
        return -EFAULT;
    if (first < available && copy_to_user(user + first, tap_buffer,
                                          available - first))
        return -EFAULT;

    spin_lock_irqsave(&tap_lock, flags);
    tap_read_pos = (tap_read_pos + available) % TAP_BYTES;
    tap_used -= min(available, tap_used);
    spin_unlock_irqrestore(&tap_lock, flags);
    return available;
}

static ssize_t tap_write(struct file *file, const char __user *user,
                         size_t count, loff_t *offset)
{
    unsigned long flags;
    unsigned int first;
    unsigned int drop;

    if (!(file->f_mode & FMODE_WRITE))
        return -EBADF;
    count &= ~1U;
    if (!count || count > INJECT_BYTES)
        return count ? -EINVAL : 0;
    if (copy_from_user_fn(inject_scratch, user, count))
        return -EFAULT;

    spin_lock_irqsave(&tap_lock, flags);
    while (INJECT_BYTES - inject_used < count) {
        drop = count - (INJECT_BYTES - inject_used);
        if (drop > inject_used)
            drop = inject_used;
        inject_read_pos = (inject_read_pos + drop) & (INJECT_BYTES - 1);
        inject_used -= drop;
    }
    first = min_t(unsigned int, count, INJECT_BYTES - inject_write_pos);
    memcpy(inject_buffer + inject_write_pos, inject_scratch, first);
    if (first < count)
        memcpy(inject_buffer, inject_scratch + first, count - first);
    inject_write_pos = (inject_write_pos + count) & (INJECT_BYTES - 1);
    inject_used += count;
    spin_unlock_irqrestore(&tap_lock, flags);
    return count;
}

static unsigned int tap_poll(struct file *file, poll_table *wait)
{
    unsigned int mask = 0;
    poll_wait(file, &tap_wait, wait);
    if (tap_used)
        mask |= POLLIN | POLLRDNORM;
    return mask;
}

static const struct file_operations tap_fops = {
    .owner = THIS_MODULE,
    .open = tap_open,
    .release = tap_release,
    .read = tap_read,
    .write = tap_write,
    .poll = tap_poll,
    .llseek = no_llseek,
};

static struct miscdevice tap_device = {
    .minor = MISC_DYNAMIC_MINOR,
    .name = "voif_tap",
    .fops = &tap_fops,
    .mode = 0666,
};

static int __init voif_tap_init(void)
{
    int rc;
    lookup_fn lookup = kallsyms_lookup_name;

    spin_lock_init(&tap_lock);
    tap_buffer = kzalloc(TAP_BYTES, GFP_KERNEL);
    if (!tap_buffer)
        return -ENOMEM;

    audio_open = (audio_open_fn)lookup("AUDIO_DRIVER_Open");
    audio_ctrl = (audio_ctrl_fn)lookup("AUDIO_DRIVER_Ctrl");
    audio_close = (audio_close_fn)lookup("AUDIO_DRIVER_Close");
    register_voif = (register_voif_fn)lookup("CSL_RegisterVOIFStatusHandler");
    get_ul_buffer = (get_ul_fn)lookup("CSL_GetULVoIFBuffer");
    get_dl_buffer = (get_dl_fn)lookup("CSL_GetDLVoIFBuffer");
    voif_control = (voif_control_fn)lookup("VPRIPCMDQ_VOIFControl");
    original_voif_handler = (voif_status_cb)lookup("VOIF_Buffer_Request");
    copy_from_user_fn = (void *)lookup("__copy_from_user");
    memset_fn = (void *)lookup("memset");
    if (!audio_open || !audio_ctrl || !audio_close) {
        pr_err("voif_tap: Broadcom audio symbols unavailable\n");
        rc = -ENOENT;
        goto free_buffer;
    }

    rc = misc_register(&tap_device);
    if (rc)
        goto free_buffer;

    if (register_voif && get_ul_buffer && get_dl_buffer && voif_control &&
        copy_from_user_fn && memset_fn) {
        register_voif(direct_voif_callback);
        voif_control(3); /* duplex DL PCM + writable duplex UL PCM */
        direct_duplex = true;
        voif_active = true;
        pr_info("voif_tap: started full-duplex DSP tap/injector\n");
    } else {
        voif_handle = audio_open(AUDIO_DRIVER_VOIF);
        if (!voif_handle) {
            rc = -ENODEV;
            goto remove_device;
        }
        audio_ctrl(voif_handle, AUDIO_DRIVER_SET_VOIF_CB, voif_callback);
        audio_ctrl(voif_handle, AUDIO_DRIVER_START, NULL);
        voif_active = true;
        pr_warn("voif_tap: direct VOIF symbols missing; downlink-only mode\n");
    }
    return 0;

remove_device:
    misc_deregister(&tap_device);
free_buffer:
    kfree(tap_buffer);
    tap_buffer = NULL;
    return rc;
}

static void __exit voif_tap_exit(void)
{
    void *handle = voif_handle;
    voif_handle = NULL;
    voif_active = false;
    wake_up_interruptible(&tap_wait);
    if (handle) {
        audio_ctrl(handle, AUDIO_DRIVER_STOP, NULL);
        audio_close(handle);
    }
    if (direct_duplex) {
        voif_control(0);
        if (original_voif_handler)
            register_voif(original_voif_handler);
        direct_duplex = false;
    }
    misc_deregister(&tap_device);
    kfree(tap_buffer);
    pr_info("voif_tap: stopped (frames=%lu overruns=%lu rate=%u)\n",
            tap_frames, tap_overruns, tap_rate);
}

module_init(voif_tap_init);
module_exit(voif_tap_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Broadcom Hawaii read-only cellular downlink VOIF tap");
MODULE_AUTHOR("gsm2sip");
