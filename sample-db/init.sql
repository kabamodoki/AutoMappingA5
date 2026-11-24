-- サンプル EC ショップ 20テーブル
-- 外部キーは1本も張っていない（リレーションは sample_relations.csv で追加して試す）

CREATE TABLE categories (
  id         serial PRIMARY KEY,
  parent_id  integer,
  name       varchar(100) NOT NULL,
  sort_order integer NOT NULL DEFAULT 0
);

CREATE TABLE brands (
  id         serial PRIMARY KEY,
  name       varchar(100) NOT NULL,
  country    varchar(50)
);

CREATE TABLE suppliers (
  id         serial PRIMARY KEY,
  name       varchar(100) NOT NULL,
  phone      varchar(20),
  email      varchar(200)
);

CREATE TABLE products (
  id          serial PRIMARY KEY,
  category_id integer NOT NULL,
  brand_id    integer,
  supplier_id integer,
  name        varchar(200) NOT NULL,
  price       numeric(10,0) NOT NULL,
  created_at  timestamp NOT NULL DEFAULT now(),
  spec_01 varchar(100),
  spec_02 varchar(100),
  spec_03 varchar(100),
  spec_04 varchar(100),
  spec_05 varchar(100),
  spec_06 varchar(100),
  spec_07 varchar(100),
  spec_08 varchar(100),
  spec_09 varchar(100),
  spec_10 varchar(100),
  spec_11 varchar(100),
  spec_12 varchar(100),
  spec_13 varchar(100),
  spec_14 varchar(100),
  spec_15 varchar(100),
  spec_16 varchar(100),
  spec_17 varchar(100),
  spec_18 varchar(100),
  spec_19 varchar(100),
  spec_20 varchar(100),
  spec_21 varchar(100),
  spec_22 varchar(100),
  spec_23 varchar(100),
  spec_24 varchar(100),
  spec_25 varchar(100),
  spec_26 varchar(100),
  spec_27 varchar(100),
  spec_28 varchar(100),
  spec_29 varchar(100),
  spec_30 varchar(100),
  spec_31 varchar(100),
  spec_32 varchar(100),
  spec_33 varchar(100),
  spec_34 varchar(100),
  spec_35 varchar(100),
  spec_36 varchar(100),
  spec_37 varchar(100),
  spec_38 varchar(100),
  spec_39 varchar(100),
  spec_40 varchar(100),
  spec_41 varchar(100),
  spec_42 varchar(100),
  spec_43 varchar(100)
);

CREATE TABLE product_images (
  id         serial PRIMARY KEY,
  product_id integer NOT NULL,
  url        varchar(500) NOT NULL,
  sort_order integer NOT NULL DEFAULT 0
);

CREATE TABLE warehouses (
  id         serial PRIMARY KEY,
  name       varchar(100) NOT NULL,
  prefecture varchar(10) NOT NULL
);

CREATE TABLE inventories (
  warehouse_id integer NOT NULL,
  product_id   integer NOT NULL,
  quantity     integer NOT NULL DEFAULT 0,
  updated_at   timestamp NOT NULL DEFAULT now(),
  PRIMARY KEY (warehouse_id, product_id)
);

CREATE TABLE customers (
  id         serial PRIMARY KEY,
  name       varchar(100) NOT NULL,
  email      varchar(200) NOT NULL UNIQUE,
  created_at timestamp NOT NULL DEFAULT now(),
  profile_01 varchar(100),
  profile_02 varchar(100),
  profile_03 varchar(100),
  profile_04 varchar(100),
  profile_05 varchar(100),
  profile_06 varchar(100),
  profile_07 varchar(100),
  profile_08 varchar(100),
  profile_09 varchar(100),
  profile_10 varchar(100),
  profile_11 varchar(100),
  profile_12 varchar(100),
  profile_13 varchar(100),
  profile_14 varchar(100),
  profile_15 varchar(100),
  profile_16 varchar(100),
  profile_17 varchar(100),
  profile_18 varchar(100),
  profile_19 varchar(100),
  profile_20 varchar(100),
  profile_21 varchar(100),
  profile_22 varchar(100),
  profile_23 varchar(100),
  profile_24 varchar(100),
  profile_25 varchar(100),
  profile_26 varchar(100),
  profile_27 varchar(100),
  profile_28 varchar(100),
  profile_29 varchar(100),
  profile_30 varchar(100),
  profile_31 varchar(100),
  profile_32 varchar(100),
  profile_33 varchar(100),
  profile_34 varchar(100),
  profile_35 varchar(100),
  profile_36 varchar(100),
  profile_37 varchar(100),
  profile_38 varchar(100),
  profile_39 varchar(100),
  profile_40 varchar(100),
  profile_41 varchar(100),
  profile_42 varchar(100),
  profile_43 varchar(100),
  profile_44 varchar(100),
  profile_45 varchar(100),
  profile_46 varchar(100)
);

CREATE TABLE customer_addresses (
  id          serial PRIMARY KEY,
  customer_id integer NOT NULL,
  postal_code varchar(8) NOT NULL,
  address     varchar(300) NOT NULL
);

CREATE TABLE orders (
  id                  serial PRIMARY KEY,
  customer_id         integer NOT NULL,
  shipping_address_id integer,
  status              varchar(20) NOT NULL,
  ordered_at          timestamp NOT NULL DEFAULT now(),
  option_01 varchar(50),
  option_02 varchar(50),
  option_03 varchar(50),
  option_04 varchar(50),
  option_05 varchar(50),
  option_06 varchar(50),
  option_07 varchar(50),
  option_08 varchar(50),
  option_09 varchar(50),
  option_10 varchar(50),
  option_11 varchar(50),
  option_12 varchar(50),
  option_13 varchar(50),
  option_14 varchar(50),
  option_15 varchar(50),
  option_16 varchar(50),
  option_17 varchar(50),
  option_18 varchar(50),
  option_19 varchar(50),
  option_20 varchar(50),
  option_21 varchar(50),
  option_22 varchar(50),
  option_23 varchar(50),
  option_24 varchar(50),
  option_25 varchar(50),
  option_26 varchar(50),
  option_27 varchar(50),
  option_28 varchar(50),
  option_29 varchar(50),
  option_30 varchar(50),
  option_31 varchar(50),
  option_32 varchar(50),
  option_33 varchar(50),
  option_34 varchar(50),
  option_35 varchar(50)
);

CREATE TABLE order_items (
  order_id   integer NOT NULL,
  line_no    integer NOT NULL,
  product_id integer NOT NULL,
  quantity   integer NOT NULL,
  unit_price numeric(10,0) NOT NULL,
  PRIMARY KEY (order_id, line_no)
);

CREATE TABLE payments (
  id       serial PRIMARY KEY,
  order_id integer NOT NULL,
  method   varchar(20) NOT NULL,
  amount   numeric(10,0) NOT NULL,
  paid_at  timestamp
);

CREATE TABLE shipments (
  id           serial PRIMARY KEY,
  order_id     integer NOT NULL,
  warehouse_id integer NOT NULL,
  tracking_no  varchar(50),
  shipped_at   timestamp
);

CREATE TABLE shipment_items (
  shipment_id integer NOT NULL,
  order_id    integer NOT NULL,
  line_no     integer NOT NULL,
  quantity    integer NOT NULL,
  PRIMARY KEY (shipment_id, order_id, line_no)
);

CREATE TABLE coupons (
  id          serial PRIMARY KEY,
  code        varchar(20) NOT NULL UNIQUE,
  discount    numeric(10,0) NOT NULL,
  expires_at  timestamp
);

CREATE TABLE order_coupons (
  order_id  integer NOT NULL,
  coupon_id integer NOT NULL,
  PRIMARY KEY (order_id, coupon_id)
);

CREATE TABLE reviews (
  id          serial PRIMARY KEY,
  product_id  integer NOT NULL,
  customer_id integer NOT NULL,
  rating      smallint NOT NULL,
  body        text,
  created_at  timestamp NOT NULL DEFAULT now()
);

CREATE TABLE staff (
  id    serial PRIMARY KEY,
  name  varchar(100) NOT NULL,
  role  varchar(20) NOT NULL
);

CREATE TABLE audit_logs (
  id         bigserial PRIMARY KEY,
  staff_id   integer,
  action     varchar(50) NOT NULL,
  created_at timestamp NOT NULL DEFAULT now()
);

CREATE TABLE app_settings (
  key   varchar(50) PRIMARY KEY,
  value text
);

-- 少しだけデータ
INSERT INTO categories (parent_id, name) VALUES (NULL, '食品'), (1, 'お菓子'), (NULL, '日用品');
INSERT INTO brands (name, country) VALUES ('サンプル製菓', '日本'), ('Acme', 'USA');
INSERT INTO suppliers (name) VALUES ('東京卸');
INSERT INTO products (category_id, brand_id, supplier_id, name, price) VALUES (2, 1, 1, 'チョコクッキー', 300), (3, 2, 1, '歯ブラシ', 150);
INSERT INTO warehouses (name, prefecture) VALUES ('関東倉庫', '埼玉県');
INSERT INTO inventories (warehouse_id, product_id, quantity) VALUES (1, 1, 100), (1, 2, 50);
INSERT INTO customers (name, email) VALUES ('山田 花子', 'hanako@example.com');
INSERT INTO customer_addresses (customer_id, postal_code, address) VALUES (1, '100-0001', '東京都千代田区1-1');
INSERT INTO orders (customer_id, shipping_address_id, status) VALUES (1, 1, 'ordered');
INSERT INTO order_items VALUES (1, 1, 1, 2, 300), (1, 2, 2, 1, 150);
INSERT INTO app_settings VALUES ('tax_rate', '10');
