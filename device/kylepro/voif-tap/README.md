# Kylepro full-duplex VOIF bridge

This optional kernel module exposes the Broadcom modem DSP's cellular audio
through `/dev/voif_tap`:

- reads return the untouched GSM downlink as mono S16LE PCM;
- writes replace the physical handset microphone with mono 8 kHz S16LE PCM
  while a writer has the device open;
- closing the writer immediately restores the normal handset microphone.

It uses the DSP's enhanced VOIF mode and is specific to the Samsung GT-S7580
and GT-S7582 LineageOS 14.1 Broadcom Hawaii kernel. The checked-in `.ko` was
built for this exact vermagic:

```
3.4.5-NG-g80e3f105 SMP preempt mod_unload modversions ARMv7
```

Do not load it on a different kernel. Rebuild from `voif_tap.c` after checking
the running kernel's vermagic and symbol CRCs. A mismatched out-of-tree module
can crash the device.

The companion `gsm2sip-audio.rc` loads the module after boot and grants the
app access to the device. The Android app opens it only for an active RTP
bridge, downsamples decoded G.722 from 16 kHz to 8 kHz for the GSM uplink, and
keeps the existing 8 kHz GSM-to-SIP capture path.
