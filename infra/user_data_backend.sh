#!/bin/bash
# バックエンドのサーバーの初期設定（docs/infrastructure.md）
# EC2 を作ったときに1回だけ実行される。アプリ本体（jar）と設定（backend.env）は deploy.sh が配置する
# Terraform の templatefile で読み込むため、$${...} は Terraform の値の埋め込みになる（シェルの変数は $VAR の形で書く）
set -eux

timedatectl set-timezone Asia/Tokyo

# Spring Boot を Java 25（Amazon Corretto 25）で動かす。サーバーではビルドしないので、実行に要る分（headless）だけ入れる。
# AMI の版によっては Amazon Linux の標準のリポジトリにまだないので、そのときは Corretto の公式リポジトリを足して入れる
if ! dnf install -y java-25-amazon-corretto-headless; then
  rpm --import https://yum.corretto.aws/corretto.key
  curl -fsSL -o /etc/yum.repos.d/corretto.repo https://yum.corretto.aws/corretto.repo
  dnf install -y java-25-amazon-corretto-headless
fi

# メモリ（1GB）不足への安全弁として、1GB のスワップファイルを作る
if [ ! -f /swapfile ]; then
  fallocate -l 1G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# アプリを動かすユーザー（ログインはできない）
id -u ${project_name} > /dev/null 2>&1 || useradd --system --no-create-home --shell /sbin/nologin ${project_name}

# jar と設定を置く場所。設定（backend.env）にはパスワードと JWT の署名鍵が入るので、root だけが読めるようにする
# （systemd が root で読み込んでから、アプリのユーザーに環境変数として渡す）
mkdir -p /opt/${project_name}
chown root:root /opt/${project_name}
chmod 755 /opt/${project_name}

# Spring Boot を常駐させる。jar が置かれるまでは起動しない（ConditionPathExists）。
# 起動するたびに Flyway がテーブルを作成・更新する。ログは journald に出る（journalctl -u ${project_name}-backend）
cat > /etc/systemd/system/${project_name}-backend.service << 'EOF'
[Unit]
Description=SNS app backend (Spring Boot)
After=network-online.target
Wants=network-online.target
ConditionPathExists=/opt/${project_name}/backend.jar

[Service]
User=${project_name}
WorkingDirectory=/opt/${project_name}
EnvironmentFile=/opt/${project_name}/backend.env
# メモリ 1GB のサーバーなので、Java が使うメモリの上限を決めておく
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar /opt/${project_name}/backend.jar --spring.profiles.active=production
# Spring Boot は SIGTERM で止めると 143 で終わる（正常な停止として扱う）
SuccessExitStatus=143
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable ${project_name}-backend.service

# 止め忘れで無料利用枠（2台合計で月750時間）を超えないよう、毎日決まった時刻に停止する
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
