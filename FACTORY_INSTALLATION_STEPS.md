# Factory Installation Steps
### How to Install Bootstrap Agent on Each Device

---

You will need:
- A Windows or Mac computer
- A USB cable
- The file `BootstrapAgent.apk` (provided by your developer)

---

## One-Time Computer Setup (First Time Only)

1. Install **Android Debug Bridge (ADB)** on your computer - your developer will give you the installer.
2. Save the file `BootstrapAgent.apk` to your Desktop.

---

## Steps for Every Device You Produce

**Step 1** - Turn on the Android device.

**Step 2** - Enable USB Debugging on the device:
- Go to **Settings > About Device**
- Tap **Build Number** 7 times until you see *You are now a developer*
- Go back to **Settings > Developer Options**
- Turn **USB Debugging ON**

**Step 3** - Plug the device into your computer using the USB cable.

**Step 4** - A pop-up will appear on the device screen saying **Allow USB Debugging?**
- Tap **Allow**

**Step 5** - On your computer, open:
- **Command Prompt** (Windows): press `Windows Key + R`, type `cmd`, press Enter
- **Terminal** (Mac): open the Terminal app

**Step 6** - Type the following and press Enter:

    adb install BootstrapAgent.apk

Wait 10-15 seconds. You will see the word **Success** when it is done.

**Step 7** - Type the following and press Enter:

    adb shell appops set com.bootstrap.agent REQUEST_INSTALL_PACKAGES allow

**Step 8** - Unplug the USB cable.

**The device is ready to ship.**

---

## Before Boxing the Device - Quick Checklist

- [ ] USB cable is unplugged
- [ ] You saw **Success** in Step 6
- [ ] **Do NOT** install the Translation App - it will install itself automatically when the customer connects to Wi-Fi

---

## What Happens After the Customer Gets the Device

The customer simply:
1. Powers on the device
2. Connects to Wi-Fi

The Translation App will **download and install itself automatically**. No further action needed from the factory or the customer.
