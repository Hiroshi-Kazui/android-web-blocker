# android-web-blocker (AnimeBlock for Android)

[web-blocker](../web-blocker) (Windows 版) のスマホ (Android) 版です。
アニメを中心とした動画配信サイトへのアクセスをブロックします。サイトは 2 種類に分かれます。

- **恒久ブロック**: 時間に関係なく常にブロック。一度入れたら外せません
- **時間帯ブロック**: 毎日決まった時間帯 (既定 21:00〜07:00) だけブロック。時間外なら外せます

## 仕組み

| 層 | 内容 |
|---|---|
| 端末内 VPN (DNS) | 端末の DNS を、このアプリの中の仮想 DNS サーバーに向ける。対象ドメインとそのサブドメインには 0.0.0.0 を返し、それ以外は今つながっている回線 (Wi-Fi があれば Wi-Fi) の DNS サーバーへ転送する。全アプリ・全ブラウザに効く。DNS 以外の通信はアプリを通らない |
| 保護 (デバイスオーナー) | アンインストール・VPN の解除・初期化・セーフモード・日時変更・プライベート DNS の変更・別ユーザーの追加・強制停止を禁止する。ファイルや PC (adb) からのアプリのインストールも禁止し、保護を外す版への差し替えを防ぐ。Chrome 系ブラウザの暗号化 DNS も止める |
| 時刻 | 時刻サーバー (NTP) と同期し、端末の時計を変えても判定がずれない |

Windows 版との違い: DNS で止めるため、**パス単位のブロック (例: `amazon.co.jp/gp/video`) はできません**。
そのため Amazon 本体は止めず、Prime Video 専用ドメインの `primevideo.com` だけを止めています。
逆に、指定したドメインのサブドメインはすべて止まります (例: `nicovideo.jp` は静画・大百科も止まる)。

## ビルド

JDK 17 と Android SDK (platform 35) が必要です。Android 9 (Fire OS 7) 以降で動きます。Android 9 (Fire OS 7) 以降で動きます。

```
gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk ができる
gradlew testDebugUnitTest    # 単体テスト
```

更新版を上書きインストールするには同じ署名鍵が必要です。debug ビルドの鍵は `%USERPROFILE%\.android\debug.keystore` です。

## インストール

1. 端末の「設定 → デバイス情報 → ビルド番号」を 7 回タップして開発者向けオプションを出し、USB デバッグをオンにする
2. PC と USB で接続して実行する

   ```
   adb install app\build\outputs\apk\debug\app-debug.apk
   ```

3. アプリを開いて「開始」を押し、VPN の接続リクエストで「OK」を押す

### 保護をかける

保護をかけるには、端末のアカウントを一時的にすべて削除する必要があります (Android の仕様)。
Google 以外のアプリが作る見えないアカウント (例: Instagram・Facebook 系) も対象です。

1. 「設定 → パスワードとアカウント」で全アカウントを削除する。残っていないかは `adb shell dumpsys account` の `Accounts: 0` で確認する。
   消えないものは、そのアカウントを作っているアプリをアンインストールする
2. PC から実行する (アプリの画面にも表示されます)

   ```
   adb shell dpm set-device-owner io.github.hiroshikazui.webblocker/.AdminReceiver
   ```

3. `Success` と出たら保護が有効になり、PC からの接続は切れる。アプリに「保護: 有効」と出ることを確認する
4. アカウントを追加し直す。Google アカウントの追加画面は「仕事用アカウント」と表示されることがあるが、個人のアカウントでログインできる

### アプリの更新

保護中はファイルや PC からのインストールを禁止しています。更新するときは次の手順で一時的に許可します。

1. ブロック時間外に、アプリの「アプリの更新」で「更新を許可」を押す (15 分間)。この間は USB デバッグが自動でオンに戻る
2. PC から `adb install -r app\build\outputs\apk\debug\app-debug.apk` を実行する (または APK ファイルを端末で開いてインストールする)

15 分たつか、ブロック時間に入ると、1 分ほどで自動的に禁止に戻ります。

### Fire タブレット (Fire OS 7 以降)

Fire OS 7 は Android 9 相当で、同じ APK を入れられます。手順はスマホと同じですが、次の点が違います。

- 開発者向けオプションは「設定 → デバイスオプション → タブレット端末情報 → シリアル番号」を 7 回タップすると出る。
  開発者オプション画面のいちばん上のスイッチがオフだと全項目がグレーアウトしているので、オンにしてから USB デバッグをオンにする
- **保護 (デバイスオーナー) はかけられない**。Fire OS には Amazon の「保護者による使用制限」が管理者として組み込まれていて、
  `dpm set-device-owner` は "the user already has a profile owner" で拒否される (Fire HD 10, Fire OS 7.3.3.1 で確認)。
  そのため VPN の解除・アンインストールは防げない
- **起動時に VPN が自動で始まるよう、常時接続 VPN を設定する**。Fire OS は起動直後に裏からサービスを始めることを禁止しているため、
  設定しないと再起動でブロックが止まる。PC から設定する場合:

  ```
  adb shell settings put secure always_on_vpn_app io.github.hiroshikazui.webblocker
  ```

- **Silk ブラウザではブロックが効かない**。VPN 動作中でも hidive.com・netflix.com が表示された (独自の DNS を使っているとみられる)
- Prime Video アプリは primevideo.com ではなく amazonvideo.com・pv-cdn.net などを使うため、これらも恒久ブロックに入れている (0.3.2)。
  買い物 (amazon.co.jp・商品画像) は止まらないことを実機で確認済み

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
- LINE の広告 (常にブロック): 広告の配信元のドメインだけを止め、トーク・通話は止めない。
  LINE 公式の一覧はないため、実機で広告表示時の問い合わせを記録して選んだ (`Sites.kt` の `LINE_ADS`)。
  トーク一覧・ニュースの広告が消え、トーク・写真は使えることを確認済み。LINE の更新で配信元が変わると、また表示される可能性がある

一覧は `app/src/main/java/io/github/hiroshikazui/webblocker/core/Sites.kt` で確認できます。

## 防げないこと

- 保護をかける前は、設定画面から VPN を切る、アプリをアンインストールすることができる
- 更新を許可している 15 分間に、保護を外す処理を持つ版 (0.2.x など) を上書きインストールする
- ブラウザ・アプリが独自の暗号化 DNS (DNS over HTTPS など) や VPN・プロキシを使う場合 (Chrome 系以外)
- IP アドレスを直接指定する通信
- 時間帯ブロックの開始直後や、サイトを追加した直後の数分間、その直前にアクセスしていたサイト。端末が DNS の結果を一時的に覚えている (キャッシュ) ためで、Android ではアプリからキャッシュを消せない。覚えている期間 (サイトごとに DNS 側で決まる) が過ぎればブロックされる想定だが、実機・エミュレータでは未確認。最初からブロックしている恒久ブロックの既定サイトには影響しない
- PC など別の端末を使う
