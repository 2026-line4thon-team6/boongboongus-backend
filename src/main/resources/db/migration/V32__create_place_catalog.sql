CREATE TABLE places (
    id VARCHAR(80) PRIMARY KEY,
    name TEXT NOT NULL,
    region TEXT NOT NULL,
    address TEXT NOT NULL,
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    category VARCHAR(30) NOT NULL CHECK (category IN ('SEA', 'NIGHT_VIEW', 'NEARBY_CAFE', 'NATURE', 'CAR_CAMPING')),
    hashtags TEXT ARRAY NOT NULL DEFAULT CAST(ARRAY[] AS TEXT ARRAY),
    thumbnail_url TEXT,
    photos TEXT ARRAY NOT NULL DEFAULT CAST(ARRAY[] AS TEXT ARRAY),
    introduction TEXT,
    parking_info TEXT,
    recommended_visit_time TEXT,
    main_roads TEXT ARRAY NOT NULL DEFAULT CAST(ARRAY[] AS TEXT ARRAY),
    recommendation_order INTEGER CHECK (recommendation_order > 0),
    popular_order INTEGER CHECK (popular_order > 0)
);

-- Seoul Forest remains addressable by its existing ID with no list placement.
-- https://data.seoul.go.kr/dataList/OA-394/S/1/datasetView.do
INSERT INTO places (id, name, region, address, latitude, longitude, category, hashtags)
VALUES ('plc_seoul_forest', '서울숲', '서울 성동구', '서울특별시 성동구 뚝섬로 273',
        37.5430717, 127.0417982, 'NATURE', ARRAY['산책', '공원']);

-- Keep the category order currently returned by the recommendations API.
INSERT INTO places (id, name, region, address, latitude, longitude, category, hashtags,
    thumbnail_url, photos, introduction, recommendation_order)
VALUES
    -- 시흥시 문화관광: https://www.siheung.go.kr/cultour/contents.do?mId=0401110000
    ('plc_oido', '시흥 오이도', '경기 시흥시', '경기도 시흥시 오이도로 170',
     37.3454554, 126.6875106, 'SEA', ARRAY['일몰', '해안도로'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/oido.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/oido.webp'],
     '오이도 빨강등대와 서해 바다를 둘러볼 수 있어요.', 3),
    -- 열린관광 49b013b9-78b8-40e3-8d40-34584f3c68ee: 심곡매표소 길안내 핀
    ('plc_heonhwaro', '강릉 헌화로', '강원 강릉시', '강원특별자치도 강릉시 강동면 심곡리 114-3',
     37.6662, 129.0542, 'SEA', ARRAY['바다', '해안도로'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/heonhwaro.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/heonhwaro.webp'],
     '금진해변과 심곡항을 잇는 해안도로예요. 목적지는 심곡항 쪽 입구로 안내해요.', 2),
    -- VisitKorea 105441
    ('plc_eurwangni', '인천 을왕리 해수욕장', '인천 영종구', '인천광역시 영종구 용유서로302번길 16-15',
     37.4468, 126.3723, 'SEA', ARRAY['바다', '노을'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/eurwangni.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/eurwangni.webp'],
     '모래사장에서 서해의 노을을 볼 수 있어요.', 1),
    -- VisitKorea 111440: 국립극장
    ('plc_namsan', '서울 남산 순환도로', '서울 중구', '서울특별시 중구 장충단로 59',
     37.5526684, 126.9996170, 'NIGHT_VIEW', ARRAY['도심드라이브', '서울'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/namsan.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/namsan.webp'],
     '남산 순환로 입구 인근의 국립극장까지 안내해요.', 2),
    -- VisitKorea 91983
    ('plc_banpo', '서울 반포한강공원', '서울 서초구', '서울특별시 서초구 신반포로11길 40',
     37.5076862, 126.9927514, 'NIGHT_VIEW', ARRAY['한강', '데이트'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/banpo.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/banpo.webp'],
     '한강 산책로에서 반포대교와 강 건너 야경을 볼 수 있어요.', 1),
    -- VisitKorea 61406
    ('plc_songdo', '인천 송도 센트럴파크', '인천 연수구', '인천광역시 연수구 컨벤시아대로 160',
     37.3903457, 126.6435841, 'NIGHT_VIEW', ARRAY['야경', '도시전망'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/songdo.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/songdo.webp'],
     '도심 수로를 따라 산책하며 송도 야경을 즐길 수 있어요.', 3),
    -- 남양주시 문화관광 resrceNo=365: 물의정원
    ('plc_bukhangang_cafe', '남양주 북한강 카페거리', '경기 남양주시', '경기도 남양주시 조안면 북한강로 398',
     37.5575057, 127.3132180, 'NEARBY_CAFE', ARRAY['리버뷰', '카페투어'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/bukhangang-cafe.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/bukhangang-cafe.webp'],
     '북한강변 카페를 둘러보기 좋은 코스예요. 목적지는 물의정원으로 안내해요.', 1),
    -- VisitKorea 104721
    ('plc_heyri', '파주 헤이리 예술마을', '경기 파주시', '경기도 파주시 탄현면 헤이리마을길 70-21',
     37.7880093, 126.6992980, 'NEARBY_CAFE', ARRAY['감성카페', '데이트'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/heyri.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/heyri.webp'],
     '마을을 걸으며 카페와 전시 공간을 둘러볼 수 있어요.', 2),
    -- 테라로사 서종점: https://www.terarosa.com/store/detail/?id=12
    ('plc_seojong_cafe', '양평 서종 카페거리', '경기 양평군', '경기도 양평군 서종면 북한강로 992',
     37.62484, 127.35491, 'NEARBY_CAFE', ARRAY['자연뷰', '힐링'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/seojong-cafe.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/seojong-cafe.webp'],
     '북한강을 따라 카페를 둘러보기 좋은 코스예요. 목적지는 테라로사 서종점으로 안내해요.', 3),
    -- VisitKorea 87086
    ('plc_dumulmeori', '양평 두물머리', '경기 양평군', '경기도 양평군 양서면 두물머리길 145',
     37.5336210, 127.3174699, 'NATURE', ARRAY['자연', '강변'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/dumulmeori.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/dumulmeori.webp'],
     '북한강과 남한강이 만나는 강변을 따라 산책할 수 있어요.', 2),
    -- VisitKorea 93730: 청평호반로의 쁘띠프랑스
    ('plc_cheongpyeong', '가평 청평호반', '경기 가평군', '경기도 가평군 청평면 호반로 1063',
     37.7153159, 127.4902950, 'NATURE', ARRAY['호수', '자연 풍경'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/cheongpyeong.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/cheongpyeong.webp'],
     '청평호반을 따라 달리는 코스예요. 목적지는 호반로의 쁘띠프랑스로 안내해요.', 1),
    -- 전국관광안내소표준데이터 15013112: 산정호수 하동주차장 포천관광안내소
    ('plc_sanjeong_lake', '포천 산정호수', '경기 포천시', '경기도 포천시 영북면 산정호수로411번길 5',
     38.0656120, 127.3156898, 'NATURE', ARRAY['호수', '숲길'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/sanjeong-lake.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/sanjeong-lake.webp'],
     '호수 둘레길에 접근할 수 있는 하동주차장 쪽으로 안내해요.', 3),
    -- GoCamping 7548
    ('plc_mongsanpo', '태안 몽산포 캠핑장', '충남 태안군', '충청남도 태안군 남면 신장리 353-53',
     36.6707972, 126.2863962, 'CAR_CAMPING', ARRAY['차박', '바다'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/mongsanpo.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/mongsanpo.webp'],
     '몽산포해변 근처에 있는 오토캠핑장이에요.', 3),
    -- GoCamping 2616
    ('plc_jaraseom', '가평 자라섬 캠핑장', '경기 가평군', '경기도 가평군 가평읍 자라섬로 60',
     37.8208749, 127.5209246, 'CAR_CAMPING', ARRAY['차박', '캠핑'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/jaraseom.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/jaraseom.webp'],
     '북한강 자라섬에 있는 캠핑장이에요.', 2),
    -- GoCamping 102006
    ('plc_baengnoju', '포천 백로주 캠핑장', '경기 포천시', '경기도 포천시 영중면 호국로 2669',
     37.9812649, 127.2419948, 'CAR_CAMPING', ARRAY['차박', '힐링'],
     'https://2026-line4thon-team6-fe.vercel.app/mocks/spots/baengnoju.webp',
     ARRAY['https://2026-line4thon-team6-fe.vercel.app/mocks/spots/baengnoju.webp'],
     '포천 영중면의 백로주 캠핑앤파크로 안내해요.', 1);
