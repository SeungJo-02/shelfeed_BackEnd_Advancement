#!/bin/sh
# ──────────────────────────────────────────────────────────────
# Let's Encrypt 인증서 최초 발급 스크립트 (EC2에서 1회만 실행)
#
# 사전 조건:
#   1) api.shelfeed.co.kr A레코드가 이 EC2 공인 IP를 가리킬 것
#   2) 보안그룹 인바운드 80, 443 개방
#   3) repo 루트에서 실행: sh docker/nginx/init-letsencrypt.sh
#
# 동작: 더미 인증서로 nginx 기동 → 실제 인증서 발급 → 교체 → reload
# (인증서 닭-달걀 문제 해결을 위한 표준 절차)
# ──────────────────────────────────────────────────────────────
set -e

domain="api.shelfeed.co.kr"
email="binsama0106@gmail.com"
staging=0            # 테스트 시 1 (Let's Encrypt rate limit 회피용 staging 인증서)
rsa_key_size=4096
data_path="./certbot"
compose="docker compose -f docker-compose.prod.yml"

if [ -d "$data_path/conf/live/$domain" ]; then
  printf "기존 인증서가 있습니다(%s). 새로 덮어쓸까요? (y/N) " "$domain"
  read decision
  if [ "$decision" != "Y" ] && [ "$decision" != "y" ]; then
    echo "취소되었습니다."
    exit
  fi
fi

# 1) 권장 TLS 파라미터 다운로드 (nginx.conf가 include 함)
if [ ! -e "$data_path/conf/options-ssl-nginx.conf" ] || [ ! -e "$data_path/conf/ssl-dhparams.pem" ]; then
  echo "### 권장 TLS 설정 다운로드 ..."
  mkdir -p "$data_path/conf"
  curl -s https://raw.githubusercontent.com/certbot/certbot/master/certbot-nginx/certbot_nginx/_internal/tls_configs/options-ssl-nginx.conf > "$data_path/conf/options-ssl-nginx.conf"
  curl -s https://raw.githubusercontent.com/certbot/certbot/master/certbot/certbot/ssl-dhparams.pem > "$data_path/conf/ssl-dhparams.pem"
fi

# 2) 더미 인증서 생성 (nginx 443 블록이 기동되려면 인증서 파일이 있어야 함)
echo "### 더미 인증서 생성 ($domain) ..."
live_path="/etc/letsencrypt/live/$domain"
mkdir -p "$data_path/conf/live/$domain"
$compose run --rm --entrypoint "\
  openssl req -x509 -nodes -newkey rsa:$rsa_key_size -days 1 \
    -keyout '$live_path/privkey.pem' \
    -out '$live_path/fullchain.pem' \
    -subj '/CN=localhost'" certbot

# 3) nginx 기동 (더미 인증서로)
echo "### nginx 기동 ..."
$compose up --force-recreate -d nginx

# 4) 더미 인증서 삭제
echo "### 더미 인증서 삭제 ..."
$compose run --rm --entrypoint "\
  rm -Rf /etc/letsencrypt/live/$domain && \
  rm -Rf /etc/letsencrypt/archive/$domain && \
  rm -Rf /etc/letsencrypt/renewal/$domain.conf" certbot

# 5) 실제 Let's Encrypt 인증서 발급 (webroot http-01)
echo "### Let's Encrypt 인증서 발급 ($domain) ..."
case "$staging" in 1) staging_arg="--staging" ;; *) staging_arg="" ;; esac
$compose run --rm --entrypoint "\
  certbot certonly --webroot -w /var/www/certbot \
    $staging_arg \
    --email $email \
    -d $domain \
    --rsa-key-size $rsa_key_size \
    --agree-tos \
    --no-eff-email \
    --force-renewal" certbot

# 6) 실제 인증서로 nginx reload
echo "### nginx reload ..."
$compose exec nginx nginx -s reload

echo ""
echo "✅ 완료! https://$domain 에서 확인하세요."
echo "   이후 갱신은 certbot 컨테이너가 자동 처리합니다 (12h 주기 renew + nginx 6h reload)."
