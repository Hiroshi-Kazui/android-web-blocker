# android-web-blocker (AnimeBlock for Android)

[web-blocker](../web-blocker) (Windows 版) のスマホ (Android) 版です。
アニメを中心とした動画配信サイトへのアクセスをブロックします。サイトは 2 種類に分かれます。

- **恒久ブロック**: 時間に関係なく常にブロック。一度入れたら外せません
- **時間帯ブロック**: 毎日決まった時間帯 (既定 21:00〜07:00) だけブロック。時間外なら外せます

**アンインストールの手段はありません。** デバイスオーナーとして登録すると、端末を初期化しない限り外せなくなります。

## 仕組み

| 層 | 内容 |
|---|---|
| 端末内 VPN (DNS) | 端末の DNS を、このアプリの中の仮想 DNS サーバーに向ける。対象ドメインとそのサブドメインには 0.0.0.0 を返し、それ以外は本来の DNS サーバーへ転送する。全アプリ・全ブラウザに効く。DNS 以外の通信はアプリを通らない |
| 暗号化 DNS の無効化 | プライベート DNS (DNS over TLS) の設定変更を禁止し、Chrome / Edge / Brave の DNS over HTTPS を管理ポリシーで止める |
| 時刻 | 時刻サーバー (NTP) と同期し、端末の時計を変えても判定がずれない。日時・タイムゾーンの設定変更自体も禁止する |
| アプリ保護 (デバイスオーナー) | アンインストール・強制停止・データ消去を禁止。常時接続 VPN に固定し、VPN 設定の変更を禁止。設定画面からの初期化・セーフモード・別ユーザー・USB デバッグ (adb) を禁止 |

Windows 版との違い: DNS で止めるため、**パス単位のブロック (例: `amazon.co.jp/gp/video`) はできません**。
そのため Amazon 本体は止めず、Prime Video 専用ドメインの `primevideo.com` だけを止めています。
逆に、指定したドメインのサブドメインはすべて止まります (例: `nicovideo.jp` は静画・大百科も止まる)。

## ビルド

JDK 17 と Android SDK (platform 35) が必要です。

```
gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk ができる
gradlew testDebugUnitTest    # 単体テスト
```

**注意: 更新版を入れるには同じ署名鍵が必要です。** debug ビルドの鍵は `%USERPROFILE%\.android\debug.keystore` です。
これを失うと、保護を有効にした端末へ新しい版を入れられなくなります。

## インストールと保護の有効化

1. 端末の「設定 → デバイス情報 → ビルド番号」を 7 回タップして開発者向けオプションを出し、USB デバッグをオンにする
2. **端末の Google アカウントなど、すべてのアカウントを一時的に削除する** (デバイスオーナーの登録条件)
3. PC と USB で接続して実行する

   ```
   adb install app\build\outputs\apk\debug\app-debug.apk
   adb shell dpm set-device-owner io.github.hiroshikazui.webblocker/.AdminReceiver
   ```

4. アカウントを元に戻す
5. アプリを開き、状態が「ブロック: 動作中」「保護: 有効」になっていることを確認する

**注意: 手順 3 の時点から恒久ブロックが始まり、端末を初期化しない限り解除できません。** 保護を有効にすると adb も使えなくなります。

保護を有効にしないまま使うこともできます (アプリの「開始」ボタンで VPN を許可する)。その場合は設定から VPN やアプリを外せます。

## 使い方

アプリの画面から操作します。

- **サイトを追加**: ドメインを入力して追加 (いつでも可)。「恒久ブロック」にチェックすると二度と外せない
- **外す**: 時間帯ブロックのサイトだけ、ブロック時間外のみ
- **ブロック時間の変更**: ブロック時間外のみ

## 既定のブロック対象

- 恒久ブロック: dアニメストア、Lemino、ABEMA、Netflix、Prime Video、U-NEXT (書籍含む)、DMM (dmm.com 全体)、Disney+、Hulu、
  FOD、TVer、TELASA、バンダイチャンネル、ニコニコ (nicovideo.jp 全体)、Crunchyroll、HIDIVE、bilibili (漫画含む)、
  非公式アニメ視聴サイト、民放の見逃し配信、Rakuten Viki
- 時間帯ブロック: YouTube (YouTube Music を含む)

一覧は `app/src/main/java/io/github/hiroshikazui/webblocker/core/Sites.kt` で確認できます。

## 防げないこと

- リカバリーモード (電源 + 音量ボタン) からの初期化。初期化すればすべて外れる
- ポリシーに対応していないブラウザ・アプリが独自の暗号化 DNS や VPN・プロキシを使う場合
- IP アドレスを直接指定する通信
- 時刻サーバーへの通信を遮断したうえで、再起動前に時計を進める (日時の変更自体は禁止している)
- PC など別の端末を使う
