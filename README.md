# a5er-auto-layout

A5:SQL Mk-2 の ER 図ファイル（.a5er）を手助けする小さなツール集です。Python 3.10 以上（標準ライブラリのみ）で動きます。
どのツールも**元のファイルは書き換えず**、別のファイルに出力します。

| やりたいこと | bat（ドラッグ＆ドロップ） | 出力 |
|---|---|---|
| テーブルの配置を自動で整列 | `a5er_layout.bat` に .a5er | `元の名前_layout.a5er` |
| 論理名を CSV に書き出す | `names_export.bat` に .a5er | `元の名前_names.csv` |
| CSV の論理名を反映する | `names_import.bat` に .a5er と .csv を一緒に | `元の名前_names.a5er` |
| リレーションを CSV に書き出す | `relations_export.bat` に .a5er | `元の名前_relations.csv` |
| CSV のリレーションを追加する | `relations_import.bat` に .a5er と .csv を一緒に | `元の名前_relations.a5er` |
| **論理名 → リレーション → 整列を一括** | `all_in_one.bat` に .a5er と CSV（1〜2個）を一緒に | `元の名前_all.a5er` |

## おすすめの流れ（DB からリバース生成したあと）
1. `names_export.bat` で論理名 CSV を出し、Excel で論理名を埋める
2. リレーション CSV を用意する
3. `all_in_one.bat` に .a5er と 2 つの CSV をまとめて落とす → A5:SQL Mk-2 で開く

一括モードは CSV の種類を1行目のヘッダ（`テーブル物理名…` / `親テーブル…`）で見分けます。CSV を付けずに .a5er だけ落とすと整列だけ行います。
```
python a5er_csv.py all 入力.a5er [CSV ...] [-o 出力.a5er] [--keep-lines]
```

## 整列（a5er_layout.py）
```
python a5er_layout.py 入力.a5er [-o 出力.a5er] [--mode compact|layered|grid] [--gap-x 60] [--gap-y 100] [--max-width 3000] [--margin 50] [--keep-lines]
```
- `compact`（既定）: 線が重ならないことを優先して、全体をなるべく小さく、正方形に近くまとめます
  - 上下だけでなく横からもつなぎます。縦長のテーブルの横に、小さいテーブルを並べます
  - 線がほかのテーブルを突き抜けず、ほかの線と重ならない位置に、テーブルと線の曲がる位置（`Bar1`〜`Bar3`）を決めます
  - 置く順番を何通りか試して、一番良いものを採ります（20 テーブルで数十秒かかります）
  - どうしても重ならずに引けない線があれば、その本数を表示します（その線は元のまま）
- `layered`: リレーションの親を上、子を下の段に並べます（線の位置も重ならないように決めますが、引けない線が残りやすいです）
- `grid`: 全テーブルを名前順の格子に並べます（線は変えません）
- ページが複数あればページごとに整列します
- 線に触りたくないときは `--keep-lines` を付けます（テーブルの位置だけ変えます）
- A5:SQL Mk-2 のリバース生成ファイルにはテーブルの大きさが入っていないため、表示される名前の文字数から大きさを見積もっています（実機の画面で測った値。FontSize に合わせて拡大縮小）

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

## リレーション CSV
```
親テーブル,子テーブル,親カラム,子カラム,親側種別,子側種別,依存
users,orders,id,user_id,2,3,0
orders,order_items,"id,shop_id","order_id,shop_id",,,
```
- 複合キーはカンマ区切りで1つのセルに入れます（カラムの数は親と子でそろえる）
- 種別・依存は A5:SQL Mk-2 の `RelationType1` / `RelationType2` / `Dependence` の値そのままです。空なら 2 / 3 / 0（ふつうの 1 対多）
- すでにあるリレーションは追加しません

## CSV の文字コード
書き出しは BOM 付き UTF-8（Excel でそのまま開けます）。読み込みは UTF-8（BOM の有無どちらも）と Shift_JIS に対応しています。

## 動作確認用のサンプル DB（sample-db/）
EC ショップ風の 20 テーブルが入った PostgreSQL です。
```
cd sample-db
docker compose up -d      # 起動（止めるときは docker compose down）
```
- 接続先: `localhost:15432` / DB `sampledb` / ユーザー `sample` / パスワード `sample`
- 外部キーは張っていません（リバース生成した ER 図はリレーション 0 本になります）
- 縦長のテーブルで配置が崩れやすいよう、products・customers は 50 カラム、orders は 40 カラムにしています
- `sample_relations.csv`: 全リレーション 22 本（自己参照1本・複合キー1本・依存リレーション 7 本を含む）
- `sample_relations_errors.csv`: わざとスキップされる3行（無いテーブル・無いカラム・カラム数の違い）
- `sample_names.csv`: 全テーブル・全カラムの日本語論理名（226 行）

## テスト
```
python -m unittest discover -s tests
```
