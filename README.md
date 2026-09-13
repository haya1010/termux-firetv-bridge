# Termux ↔ Fire TV ChatGPT Bridge (MVP)

Android の ChatGPT 画面を Fire TV に低遅延表示し、Fire TV リモコンを Android の操作へ戻す最小構成です。

## 構成

```text
Android phone (192.168.0.10)
  ChatGPT app
  Bridge APK
    MediaProjection -> WebRTC video
    Fire TV commands -> Accessibility gestures
    output audio level -> WebSocket
  Termux
    signaling/web server :8080
             ^                 |
             | WebSocket      | HTTP + WebRTC
             v                 v
Fire TV (192.168.0.42)
  Chihiro Bridge APK
    native WebRTC renderer
    layered Chihiro rig
  remote keys -> WebSocket
  avatar mouth <- audio level
```

Termux は signaling と受信ページ配信を担当します。Android の仕様上、他アプリの画面取得と操作注入は Termux だけではできないため、同梱の補助 APK が MediaProjection と AccessibilityService を担当します。

## 1. Termux サーバー

```sh
pkg update
pkg install nodejs-lts git
cd ~/termux-firetv-bridge/server
npm install
cp .env.example .env
npm start
```

同じ LAN の端末から `http://192.168.0.10:8080/health` を開き、`ok` が返ることを確認します。Android の設定で Termux のバッテリー最適化を解除し、必要なら次を実行します。

```sh
termux-wake-lock
```

LAN 内でも接続トークンを使う場合は `.env` の `BRIDGE_TOKEN` を設定し、Android の URL を `ws://192.168.0.10:8080/ws?token=同じ値`、Fire TV の URL を `http://192.168.0.10:8080/?token=同じ値` にします。

## 2. Android 補助 APK

初回だけ APK をビルドします。最終運用時に Mac は不要です。

1. Android Studio で `android-sender` を開く。
2. SDK 35 をインストールし、debug APK をビルドする。
3. スマホへ APK をインストールする。
4. Android 設定 → ユーザー補助 → `Termux Fire TV Bridge` を有効にする。
5. アプリを開き、Server URL が `ws://192.168.0.10:8080/ws` であることを確認する。
6. `START` を押し、画面共有を許可する。
7. ChatGPT アプリへ戻る。

CLI でビルドする場合（Gradle wrapper を生成済みの環境）:

```sh
cd android-sender
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android 14 以降では画面共有の許可は毎回必要です。通知に `Fire TV bridge is running` が表示されている間だけ配信します。

## 3. Fire TV 専用アプリ

`firetv-receiver` はブラウザを使わない専用 Leanback APK です。Android の映像をネイティブ WebRTC renderer に表示し、その上にちひろを合成します。

```sh
cd firetv-receiver
./gradlew assembleDebug
adb connect 192.168.0.42:5555
adb -s 192.168.0.42:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.0.42:5555 shell monkey -p dev.termux.firetvreceiver 1
```

Android 側で START 済みなら数秒で映像が表示されます。リモコン操作:

   - 上下: Android 画面をスクロール
   - 左右: 水平スワイプ
   - 決定: 画面中央をタップ
   - 戻る: Android の戻る操作。Fire TVアプリ自身は終了しません
   - メニュー: Termux WebSocket URL の設定画面

初期値は `ws://192.168.0.10:8080/ws` です。ADBから別URLを一度だけ指定して起動することもできます。

```sh
adb -s 192.168.0.42:5555 shell am start -n dev.termux.firetvreceiver/.MainActivity --es server_url ws://192.168.0.10:8080/ws
```

## 4. ちひろモデル

`Desktop/claude` 内で見つかった既存素材は Cubism の `.moc3` ではなく、ベース・目・眉・口を重ねる 768×768 PNG リグでした。元フォルダには変更を加えず、必要なレイヤーだけ `firetv-receiver` へ複製しています。出典とハッシュは `firetv-receiver/ASSET_PROVENANCE.md` に記録しています。

専用アプリでは自動まばたきと、音量に応じた4段階の口形を実装済みです。本物のCubismモデルが別途見つかった場合も、表示層だけ交換できる構成です。

現在の口パク入力は Android の出力ミックス音量を `Visualizer` で測った値です。端末/OS が出力ミックス取得を許可しない場合は 0 のままになります。その場合、Phase 2 で `AudioPlaybackCapture` を追加します。

## 5. 自動起動（MVP確認後）

Termux:Boot を導入して `~/.termux/boot/start-firetv-bridge` に次を置きます。

```sh
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
cd "$HOME/termux-firetv-bridge/server"
npm start
```

補助 APK は端末再起動後に通知を出せますが、Android 15 以降は BOOT_COMPLETED から MediaProjection を開始できません。画面共有だけはユーザーが START と許可を行う必要があります。

## トラブルシュート

- 映像が出ない: Fire TV と Android が同一 LAN / AP isolation 無効 / TCP 8080 許可を確認。
- `receiver connected` のまま: Android アプリで STOP → START。画面共有許可は再利用できません。
- 操作されない: AccessibilityService が有効か確認。
- 黒画面: 対象アプリが secure surface を使う画面は OS がキャプチャを禁止します。
- 戻るキー: Silk がブラウザ履歴へ割り当てる場合があります。受信ページ内 Back ボタンを使用します。

## 次の段階

MVP 確認後は (1) AudioPlaybackCapture による音声転送、(2) mDNS と再接続強化、(3) 必要ならCubism SDK統合、の順に進めます。
