# Nightwave PC server

Plays the music that is already on your computer in Nightwave, over your own Wi-Fi.

1. Install Python 3 from python.org (tick "Add Python to PATH").
2. Download `nightwave_pc.py`.
3. Open a terminal in that folder and run (use your own music folder):

       python nightwave_pc.py "D:\MUSIC"

4. It prints an address such as `http://192.168.1.20:8844`.
   - **Phone browser:** open that address — Nightwave loads with a **My PC** section.
   - **Nightwave Android app:** Settings → Music on my PC → paste the address.
5. Tap the download arrow on any PC song to keep it on your phone for offline listening.

The server only shares the one folder you name, read-only, and only while it is running.
If the phone can't connect, allow Python through Windows Firewall for Private networks.
