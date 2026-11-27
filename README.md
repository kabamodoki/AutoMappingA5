# A5:SQL Mk-2 ER図ツール（AutoMappingA5）

A5:SQL Mk-2 の ER 図ファイル（.a5er）用の小さなツールです。Java 17 以上、外部ライブラリなし。

- **整列**: テーブルの配置を自動で整えます。線がテーブルを突き抜けたり、ほかの線と重なったりしないように、リレーション線の曲がる位置も決めます
- **論理名の CSV**: 書き出し・読み込み（テーブル物理名＋カラム物理名の組み合わせで指定）
- **コメントの CSV**: 書き出し・読み込み（テーブル物理名＋カラム物理名の組み合わせで指定）
- **リレーションの CSV**: 書き出し・読み込み（物理名でも論理名でも指定できます）
- **一括**: 論理名の反映 → コメントの反映 → リレーションの追加 → 整列 をまとめて実行

どの処理も**元のファイルは書き換えず**、同じフォルダに別名で出力します。

## ビルドと起動
```
mvn package
java -jar target/a5er-tool.jar
```
`target/a5er-tool.jar` をダブルクリックしても起動できます。

## 画面の使い方
1. 「.a5er ファイル」の［選ぶ］で ER 図を選ぶ
2. 必要なら「論理名 CSV」「コメント CSV」「リレーション CSV」を選ぶ
3. ボタンを押す

| ボタン | 必要なファイル | 出力 |
|---|---|---|
| 論理名CSVを書き出す | .a5er | `元の名前_names.csv` |
| コメントCSVを書き出す | .a5er | `元の名前_comments.csv`（論理名のときは `_comments_logical.csv`） |
| リレーションCSVを書き出す | .a5er | `元の名前_relations.csv`（論理名のときは `_relations_logical.csv`） |
| 整列 | .a5er | `元の名前_layout.a5er` |
| 論理名を反映 | .a5er ＋ 論理名 CSV | `元の名前_names.a5er` |
| コメントを反映 | .a5er ＋ コメント CSV | `元の名前_comments.a5er` |
| リレーションを追加 | .a5er ＋ リレーション CSV | `元の名前_relations.a5er` |
| 一括（論理名→コメント→リレーション→整列） | .a5er ＋（選んだ CSV） | `元の名前_all.a5er` |

同じ名前の出力ファイルがあれば上書きします。結果（件数・スキップした行・出力先）は画面の下に出ます。

### 並べ方
- `compact`（既定）: 上下だけでなく横からもつなぎ、全体をなるべく小さく正方形に近くまとめます。線は重ならない位置に引きます
- `layered`: リレーションの親を上、子を下の段に並べます（重ならずに引けない線が残りやすいです）
- `grid`: 全テーブルを名前順の格子に並べます（線は変えません）

### 用紙
整列したページの用紙（ページのプロパティ）も変えます。
- `A 判・横`（既定）/ `A 判・縦` / `B 判・横` / `B 判・縦`: A4〜A1（B 判は B4〜B2）のうち、全テーブルが収まる一番小さい用紙を自動で選びます。並びもその用紙の縦横比（約 1.43）に近づけます
- `変えない`: 用紙はそのままで、並びは正方形に近くまとめます
- 優先順は「線が重ならない → 用紙が小さい → 形が用紙に近い」です。縦に長いテーブルが多い図では、小さい用紙に収めた結果、用紙の中に余白が残ることがあります
- A1（B2）にも収まらないときは、その旨を表示します（A5:SQL Mk-2 では用紙からはみ出したテーブルは表示されません）
- B 判は JIS の大きさです。B1 は A5:SQL Mk-2 が扱えないため使いません
- 「線は変えない」にチェックすると、テーブルの位置だけ変えます

重ならずに引けない線があった場合は、その本数を表示します（その線は元のまま）。

## 論理名 CSV
```
テーブル物理名,カラム物理名,論理名
users,,ユーザー
users,id,ユーザーID
orders,id,注文ID
```
- **テーブル＋カラムの組み合わせ**で指定します（カラム名だけでは指定しません）。同じ `id` でもテーブルごとに別の論理名になります
- カラム物理名が空の行はテーブルの論理名です
- 論理名が空の行は何もしません。見つからないテーブル・カラムは行番号つきで表示します

## コメント CSV
```
テーブル物理名,カラム物理名,コメント
users,,会員マスタ
users,email,ログイン用。重複不可
```
- 指定のしかたは論理名 CSV と同じです（テーブル＋カラムの組み合わせ。カラムが空ならテーブルのコメント）
- コメントが空の行は何もしません（今のコメントは消しません）
- 画面の「コメント・リレーション CSV は論理名」にチェックして書き出すと、テーブル・カラムを論理名で書いた CSV（`テーブル論理名,カラム論理名,コメント`）になります。読み込みはヘッダで自動判定します（決まりは下のリレーションの論理名モードと同じ）
- A5:SQL Mk-2 はコメントの改行を `
` の 2 文字で持ちます。書き出した CSV でも `
` と見えます。Excel のセル内で改行した場合は、読み込むときに `
` に直します

## リレーション CSV
```
親テーブル,子テーブル,親カラム,子カラム,親側種別,子側種別,依存
users,orders,id,user_id,2,3,0
orders,order_items,"id,shop_id","order_id,shop_id",,,
```
- 複合キーはカンマ区切りで 1 つのセルに入れます（カラムの数は親と子でそろえる）
- 種別・依存は A5:SQL Mk-2 の `RelationType1` / `RelationType2` / `Dependence` の値そのままです。空なら 2 / 3 / 0（ふつうの 1 対多）
- すでにあるリレーションは追加しません

### 論理名で指定する
画面の「コメント・リレーション CSV は論理名」にチェックして書き出すと、テーブル・カラムを論理名で書いた CSV になります。
```
親テーブル論理名,子テーブル論理名,親カラム論理名,子カラム論理名,親側種別,子側種別,依存
顧客,注文,顧客ID,顧客ID,2,3,0
```
- 読み込みは 1 行目のヘッダで物理名か論理名かを自動で見分けます（ヘッダが無い CSV だけ、チェックに従います）
- 論理名が見つからなければ物理名でも探します。論理名が空のテーブル・カラムは、書き出し時に物理名を書きます
- 同じ論理名のテーブル（またはテーブル内のカラム）が 2 つ以上あると決められないので、その行はスキップして表示します

CSV の書き出しは BOM 付き UTF-8（Excel でそのまま開けます）。読み込みは UTF-8（BOM の有無どちらも）と Shift_JIS に対応しています。

## コマンドでも使えます
```
java -jar target/a5er-tool.jar layout           入力.a5er [-o 出力] [--mode compact|layered|grid] [--keep-lines]
java -jar target/a5er-tool.jar export-names     入力.a5er [-o 出力.csv]
java -jar target/a5er-tool.jar import-names     入力.a5er 論理名.csv [-o 出力.a5er]
java -jar target/a5er-tool.jar export-comments  入力.a5er [-o 出力.csv] [--logical]
java -jar target/a5er-tool.jar import-comments  入力.a5er コメント.csv [-o 出力.a5er] [--logical]
java -jar target/a5er-tool.jar export-relations 入力.a5er [-o 出力.csv] [--logical]
java -jar target/a5er-tool.jar import-relations 入力.a5er リレーション.csv [-o 出力.a5er] [--logical]
java -jar target/a5er-tool.jar all              入力.a5er [CSV ...] [-o 出力.a5er] [--mode ...] [--keep-lines] [--logical]
```
`--logical` は、コメント・リレーションの CSV を論理名で書き出す／ヘッダの無い CSV を論理名として読む指定です。
`layout` と `all` には `--paper a-landscape|a-portrait|b-landscape|b-portrait|keep`（用紙。既定 a-landscape）も付けられます。
`all` は CSV の種類を 1 行目のヘッダで見分けます。

## Salesforce の定義から CSV を作る（補助ツール SfToCsv）
Salesforce のオブジェクト定義から、このツールでそのまま読める CSV を 3 つ作ります。
- `sf_names.csv`（論理名）: オブジェクトの API 名 → 表示ラベル、項目の API 名 → 表示ラベル
- `sf_relations.csv`（リレーション）: 参照・主従関係の項目ごとに「参照先.Id → その項目」
- `sf_comments.csv`（コメント）: 説明（メタデータの description。組織から取るときは inlineHelpText）

```
java -cp target/a5er-tool.jar a5er.SfToCsv            画面で使う
java -cp target/a5er-tool.jar a5er.SfToCsv folder メタデータのフォルダ [-o 出力フォルダ] [--objects A__c,B__c] [--all-relations]
java -cp target/a5er-tool.jar a5er.SfToCsv org    sf の組織の別名      [同上]
java -cp target/a5er-tool.jar a5er.SfToCsv api    インスタンスURL アクセストークン [同上] [--api 60.0]
```
- **folder**: `sf project retrieve start` などで取ってきたメタデータ（`objects/` の SFDX 形式・`.object` 形式どちらも）を読みます。組織にはつなぎません。メタデータに無い `Id`・`Name` は足します
- **org**: Salesforce CLI（`sf`）でログイン済みの組織の別名を指定すると、その接続情報で REST API から定義を取ります
- 対象は既定でカスタムオブジェクト（`__c`）全部。`--objects` で標準オブジェクトも含めて指定できます
- リレーションは既定で、両端とも対象のオブジェクトのものだけ出します（所有者・作成者などの User への参照を除くため）。全部出すときは `--all-relations`
- 物理名は API 名で出します。ER 図側のテーブル名・カラム名が小文字でも、大文字小文字を区別せずに照合します
- ソースは `src/main/java/a5er/SfToCsv.java` の 1 ファイルで、本体には依存しません。ビルドせずに `java src/main/java/a5er/SfToCsv.java folder ...` でも動きます

## 動作確認用のサンプル DB（sample-db/）
通販サイト風の 20 テーブルが入った PostgreSQL です（外部キーなし）。
```
cd sample-db
docker compose up -d      # 止めるときは docker compose down
```
- 接続先: `localhost:15432` / DB `sampledb` / ユーザー `sample` / パスワード `sample`
- `sample_names.csv`: 全テーブル・全カラムの日本語論理名
- `sample_relations.csv`: 全リレーション 22 本
- `sample_relations_errors.csv`: わざとスキップされる 3 行

## 構成
```
pom.xml
src/main/java/a5er/A5erTool.java      ER 図ツールのすべて（1 ファイル）
src/main/java/a5er/SfToCsv.java       Salesforce の定義 → CSV の補助ツール（1 ファイル）
src/test/java/a5er/                   テスト（JUnit 5。mvn package で実行されます）
sample-db/                            動作確認用 DB
```
