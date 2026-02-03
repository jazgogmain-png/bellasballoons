# bellasballoons
started as a simple balloon popping game for my toddler, evolving into a battle game over bluetooth, absolute beginner with coding, using AI to learn



---

# 🎈 Bella's Balloons: AR Battle Edition 🛡️

**"AI is the steering wheel, but you are the pilot."** A high-performance, augmented reality balloon-popping game built with **Kotlin** and **CameraX**. Designed for low-latency AR, multi-touch physics, and competitive "War Room" battles.

## 🛡️ Privacy Manifesto

This app is built on the principle of **Total Local Privacy**.

* **Zero Internet:** The app has no internet permissions. It cannot "call home" or send data to any server.
* **Local Stats:** Your username, pop counts, and streaks are stored strictly on your device's private storage.
* **Direct Connection:** Multi-player "War Room" battles happen via Peer-to-Peer Bluetooth. Data stays between the two connected devices.
* **Camera Safety:** AR mode uses a local camera feed for rendering. No images are recorded, stored, or transmitted.

## 🔑 Why the Permissions?

Android is protective of its sensors, so here is why we need what we ask for:

1. **CAMERA:** To project balloons into your living room (AR Mode).
2. **BLUETOOTH & LOCATION:** Required by Android to "scan" for your opponent's tablet. **Note:** Location is *not* used for tracking; it is a technical requirement for Bluetooth discovery on Android.
3. **VIBRATION:** For high-fidelity haptic feedback during "Fart Explosions" and pop events.

## 🚀 Battle Features

* **Gourmet AR:** Fluid, zero-lag AR using the **CameraX** API.
* **Multi-Touch Engine:** Pop with 10 fingers at once (Viking-style finger dance).
* **War Room (Bluetooth):** Connect two tablets to exchange "Warrior Profiles" and send **Stink Clouds** (green particle fog) to your opponent when you pop a balloon.
* **Adjustable Battle Timer:** Toggle between 10-second "Power Trips" or 5-minute endurance wars.
* **Smart Backgrounds:** Automatically rotates and center-crops portrait/landscape photos to fit your tablet screen perfectly.

## 🏆 Ranking System (Bisaya-Viking Hybrid)

The Battle HUD tracks your **BPM (Balloons Per Minute)** and accuracy to award titles:

* **LEGEND OF THE NORTH ❄️:** For the high-speed Viking masters.
* **BATTLE LODI 🇵🇭:** The ultimate Pinoy status.
* **STREAK SNIPER 🎯:** For 100% precision.
* **KUYAW SNAIL 🐌:** For when you're moving a bit too slow.

## 🛠️ Installation & Setup

1. **Clone the Repo:** `git clone https://github.com/jazgogmain-png/bellasballoons.git`
2. **Open in Android Studio:** Ensure you have the latest Arctic Fox or Bumblebee+ version.
3. **Sync Gradle:** Let the dependencies for CameraX and AppCompat load.
4. **Run:** Deploy to a tablet (Landscape orientation recommended).

## 👨‍🔬 Developed by

This project is part of the **Dad Science Lab**, a series of experiments in making high-quality, privacy-first software for family entertainment.

---

### Next Step for the Morning

Now that the README is prepped, would you like me to generate the **Adaptive Icon** (Viking Helmet + Balloon) assets so you can drop them into your `res` folder tomorrow?
