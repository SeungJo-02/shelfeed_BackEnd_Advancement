# Elasticsearch 8.11.3 + Nori (한글 형태소 분석기) 플러그인
# - 도서 검색 색인용 (TO-BE: 풀스캔 → 역인덱스)
# - Nori: title/author의 한글 형태소 분석 ("소설가" ↔ "소설" 매칭)

FROM elasticsearch:8.11.3

RUN bin/elasticsearch-plugin install --batch analysis-nori
