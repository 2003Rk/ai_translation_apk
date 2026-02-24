# Bootstrap Agent — Factory Setup Guide

---

## Purpose

This document explains what needs to be prepared on the factory side to enable automatic
installation of the **Translation Application** on the embedded Android translation device
after the customer connects to Wi-Fi.

---

## Deployment Method

This setup uses the **Bootstrap Agent** (Option B from Chapter 8 of the SOW).
The Bootstrap Agent is a small helper application pre-installed during manufacturing
that automatically downloads and installs the Translation App once the device connects
to Wi-Fi.

---

## Developer Deliverables

| Item | Description |
|---|---|
| `BootstrapAgent.apk` | Small downloader application |
| `TranslationApp.apk` | Main translation app (hosted on server) |

---

## Factory Responsibilities (Production Line)

1. Install `BootstrapAgent.apk` on each device during production using ADB or factory installation tools.
2. Enable installation from unknown sources in device settings.
3. Grant required permissions to Bootstrap Agent:
   - Storage Access
   - Network Access
   - Install Unknown Apps Permission
4. Verify Bootstrap Agent launches automatically on first boot.
5. Ship device with Bootstrap Agent installed — **do NOT install `TranslationApp.apk`**.

---

## Post-Shipment Operation

After the customer receives the device:

| Step | Event |
|---|---|
| 1 | Customer powers on the device |
| 2 | Customer connects the device to Wi-Fi |
| 3 | Bootstrap Agent detects internet connection |
| 4 | Agent contacts update server |
| 5 | Downloads `TranslationApp.apk` |
| 6 | Automatically starts installation process |

---

## End User Experience

The Translation App will be **automatically downloaded and installed after Wi-Fi setup**
without requiring factory ROM flashing or MDM configuration.

---

## Summary

Factory only needs to pre-install the Bootstrap Agent. The Translation App will be
automatically installed on the device after the customer connects to Wi-Fi.
