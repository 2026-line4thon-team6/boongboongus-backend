-- 대천 해수욕장을 바다 카테고리 추천의 첫 번째로 둔다. 기존 바다 추천은 한 칸씩 뒤로 민다.
UPDATE places SET recommendation_order = recommendation_order + 1
WHERE category = 'SEA' AND recommendation_order IS NOT NULL;

-- 좌표: OpenStreetMap way 591698660(대천해수욕장) 중심점. 주소: 충남 보령시 해수욕장 관광안내 기준
-- 사진: Wikimedia Commons "20150808최광모RX10DSC01567.JPG" (CC0)
INSERT INTO places (id, name, region, address, latitude, longitude, category, hashtags,
    thumbnail_url, photos, introduction, recommendation_order)
VALUES
    ('plc_daecheon', '보령 대천 해수욕장', '충남 보령시', '충청남도 보령시 머드로 123',
     36.3113, 126.5127, 'SEA', ARRAY['바다', '머드'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/daecheon.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/daecheon.webp'],
     '넓은 백사장을 따라 서해 노을을 볼 수 있어요. 여름에는 보령머드축제가 열려요.', 1);
