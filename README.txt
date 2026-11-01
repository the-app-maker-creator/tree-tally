TREE TALLY - Android app for Rick's Block
=========================================

What it does
- Worker starts a shift, puts the phone in a pocket or backpack (screen off).
- A Bluetooth camera clicker clipped to the front pocket: one click = one tree.
- Each click saves time + GPS + task (Planting, Pruning, ...). Buzz + beep to confirm.
  Double buzz / low beep = no GPS for that tree.
- Taps are kept on the phone and sent to the site when there is signal
  (every minute during the shift, and when the shift is stopped).
- They show on Rick's map under "Work done" (unlock editing to see them).

GET THE APP FILE (pick one)

A) GitHub builds it for you (easiest)
   1. Make a new private repository on github.com, e.g. "tree-tally".
   2. Upload everything in this "android" folder to it (keep the folders,
      including the hidden ".github" folder). Or tell Claude to do it.
   3. GitHub builds the app by itself (Actions tab, about 5 minutes).
   4. Open the finished run > Artifacts > TreeTally-app. It downloads a zip
      with app-debug.apk inside.

B) Android Studio on your PC
   1. Install Android Studio (free) and open this "android" folder.
   2. Let it sync, then Build > Build App Bundle(s) / APK(s) > Build APK(s).
   3. The file is app\build\outputs\apk\debug\app-debug.apk

PUT IT ON EACH PHONE
- Copy app-debug.apk to the phone (USB, email or Google Drive) and tap it.
- Android asks to allow installs from that source: allow it.
- Open Tree Tally. Fill in:
    Site address: the Cloudflare address, e.g. ricks-block.yourname.workers.dev
    Your name
    Crew code (same as the /tally web page)
- Tap Start shift. Allow location and notifications.

SET UP ON EACH PHONE (once)
- Battery: Settings > Apps > Tree Tally > Battery > Unrestricted.
  Otherwise some phones stop it after a while with the screen off.
- Pair the clicker in Bluetooth settings. If it has two buttons, use the iOS one.

TEST BEFORE THE DAY
- Start shift, lock the screen, click the clicker 5 times.
- Each click should buzz and beep. Open the app: Today should show 5.
- Phones differ. If clicks do nothing with the screen off, tell Claude the
  phone model and the clicker model.
