-- Gapyeong is operated on the private Seoul–Chuncheon section of Seoul–Yangyang.
-- Operator: https://www.hdc-holdings.com/ko/business/energy/schighway
-- Facility/ramp geometry: © OpenStreetMap contributors, https://www.openstreetmap.org/copyright
-- Facility polygons: ways 1006830968 (Chuncheon), 1006830967 (Seoul).
INSERT INTO rest_areas (id, source_id, name, highway_name, direction, latitude, longitude)
VALUES
    ('rest_d4e3f6024404d4fd19264ab6', 'sc:60:가평(춘천):하행', '가평휴게소 (춘천 방향)',
     '서울양양선', '하행', 37.7019992, 127.5459015),
    ('rest_39dbd4c570d1a8b21fec59b9', 'sc:60:가평(서울):상행', '가평휴게소 (서울 방향)',
     '서울양양선', '상행', 37.7010693, 127.5441546);

-- One-way ramps connect these mainline divergence points to the corresponding lots.
-- Check both directions against actual Kakao Mobility Byeollae–Heonhwaro routes.
-- Naerincheon is one shared facility with two distinct entrances and one stamp identity.
INSERT INTO rest_area_accesses
    (rest_area_id, approach, latitude, longitude, travel_bearing, match_radius_meters, source, verified_on)
VALUES
    ('rest_d4e3f6024404d4fd19264ab6', 'chuncheon', 37.7003378, 127.5446265, 11.1, 60,
     'OpenStreetMap way 711257235 / Kakao Mobility Byeollae-Heonhwaro', DATE '2026-11-18'),
    ('rest_39dbd4c570d1a8b21fec59b9', 'seoul', 37.7039309, 127.5455506, 196, 60,
     'OpenStreetMap way 711257289 / Kakao Mobility Heonhwaro-Byeollae', DATE '2026-11-18'),
    ('rest_551e5b6adf828f548d977b5e', 'yangyang', 37.9124568, 128.2851813, 56, 60,
     'OpenStreetMap way 1579972751 / Kakao Mobility Byeollae-Heonhwaro', DATE '2026-11-18'),
    ('rest_551e5b6adf828f548d977b5e', 'seoul', 37.9145435, 128.2894697, 246.5, 60,
     'OpenStreetMap way 548509776 / Kakao Mobility Heonhwaro-Byeollae', DATE '2026-11-18');
