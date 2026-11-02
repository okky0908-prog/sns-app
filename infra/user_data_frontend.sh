#!/bin/bash
# フロントエンドのサーバーの初期設定（docs/infrastructure.md）
# EC2 を作ったときに1回だけ実行される。画面のファイル（Vite のビルド結果 dist/）は deploy.sh が配置する
# Terraform の templatefile で読み込むため、$${...} は Terraform の値の埋め込みになる（シェルの変数は $VAR の形で書く）
set -eux

timedatectl set-timezone Asia/Tokyo

dnf install -y nginx

# 画面のファイルを置く場所。deploy.sh が ec2-user で書き込む
mkdir -p /usr/share/nginx/${project_name}
chown ec2-user:ec2-user /usr/share/nginx/${project_name}

# nginx：画面のファイルを返す。API（/api/*）は ALB がバックエンドへ振り分けるので、ここには来ない
# （nginx.conf 本体は編集しない。conf.d 側で default_server にすれば、パッケージの更新で上書きされない。何度実行しても同じ内容になる）
cat > /etc/nginx/conf.d/${project_name}.conf << 'EOF'
server {
    listen       80 default_server;
    listen       [::]:80 default_server;
    server_name  _;
    root         /usr/share/nginx/${project_name};
    index        index.html;

    gzip       on;
    gzip_types text/css application/javascript application/json image/svg+xml;

    # Vite がビルドした JS・CSS は、ファイル名に中身のハッシュが付くので長くキャッシュさせる
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    # React Router の URL（/posts/123 など）を直接開いたり再読み込みしたりしても、index.html を返して画面側で表示する。
    # index.html は、デプロイのあとすぐ新しい版が読まれるよう、毎回確認させる
    location / {
        add_header Cache-Control "no-cache";
        try_files $uri /index.html;
    }
}
EOF

systemctl enable nginx
systemctl restart nginx

# 止め忘れで無料利用枠（2台合計で月750時間）を超えないよう、毎日決まった時刻に停止する
# （instance_initiated_shutdown_behavior = "stop" のため、OS の電源を切ると EC2 は「停止」になる）
cat > /etc/systemd/system/${project_name}-auto-stop.service << 'EOF'
[Unit]
Description=Stop this EC2 instance every night to stay within the free tier

[Service]
Type=oneshot
ExecStart=/usr/bin/systemctl poweroff
EOF

cat > /etc/systemd/system/${project_name}-auto-stop.timer << 'EOF'
[Unit]
Description=Stop this EC2 instance every day at ${auto_stop_time} (Asia/Tokyo)

[Timer]
OnCalendar=*-*-* ${auto_stop_time}:00 Asia/Tokyo

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now ${project_name}-auto-stop.timer
