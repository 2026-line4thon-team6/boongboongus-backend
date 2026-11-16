package com.boongboongus.backend.domain.home;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
class HardcodedPlaceCatalog implements PlaceCatalog {
    private static final String PHOTO_BASE_URL = "https://2026-line4thon-team6-fe.vercel.app/mocks/spots/";

    // Address and coordinates: https://data.seoul.go.kr/dataList/OA-394/S/1/datasetView.do
    private static final Place SEOUL_FOREST = new Place(
            "plc_seoul_forest", "서울숲", "서울 성동구", "서울특별시 성동구 뚝섬로 273",
            new Coordinate(37.5430717, 127.0417982), null, Category.NATURE,
            List.of("산책", "공원"), List.of(), null, null, null, List.of());
    private static final List<Place> RECOMMENDED_PLACES = List.of(
            // 시흥시 문화관광: https://www.siheung.go.kr/cultour/contents.do?mId=0401110000
            recommendation("oido", "시흥 오이도", "경기 시흥시", "경기도 시흥시 오이도로 170",
                    37.3454554, 126.6875106, Category.SEA, List.of("일몰", "해안도로"),
                    "오이도 빨강등대와 서해 바다를 둘러볼 수 있어요."),
            // 열린관광 49b013b9-78b8-40e3-8d40-34584f3c68ee: 심곡매표소 길안내 핀
            recommendation("heonhwaro", "강릉 헌화로", "강원 강릉시", "강원특별자치도 강릉시 강동면 심곡리 114-3",
                    37.6662, 129.0542, Category.SEA, List.of("바다", "해안도로"),
                    "금진해변과 심곡항을 잇는 해안도로예요. 목적지는 심곡항 쪽 입구로 안내해요."),
            // VisitKorea 105441
            recommendation("eurwangni", "인천 을왕리 해수욕장", "인천 영종구", "인천광역시 영종구 용유서로302번길 16-15",
                    37.4468, 126.3723, Category.SEA, List.of("바다", "노을"),
                    "모래사장에서 서해의 노을을 볼 수 있어요."),
            // VisitKorea 111440: 국립극장
            recommendation("namsan", "서울 남산 순환도로", "서울 중구", "서울특별시 중구 장충단로 59",
                    37.5526684, 126.9996170, Category.NIGHT_VIEW, List.of("도심드라이브", "서울"),
                    "남산 순환로 입구 인근의 국립극장까지 안내해요."),
            // VisitKorea 91983
            recommendation("banpo", "서울 반포한강공원", "서울 서초구", "서울특별시 서초구 신반포로11길 40",
                    37.5076862, 126.9927514, Category.NIGHT_VIEW, List.of("한강", "데이트"),
                    "한강 산책로에서 반포대교와 강 건너 야경을 볼 수 있어요."),
            // VisitKorea 61406
            recommendation("songdo", "인천 송도 센트럴파크", "인천 연수구", "인천광역시 연수구 컨벤시아대로 160",
                    37.3903457, 126.6435841, Category.NIGHT_VIEW, List.of("야경", "도시전망"),
                    "도심 수로를 따라 산책하며 송도 야경을 즐길 수 있어요."),
            // 남양주시 문화관광 resrceNo=365: 물의정원
            recommendation("bukhangang-cafe", "남양주 북한강 카페거리", "경기 남양주시", "경기도 남양주시 조안면 북한강로 398",
                    37.5575057, 127.3132180, Category.NEARBY_CAFE, List.of("리버뷰", "카페투어"),
                    "북한강변 카페를 둘러보기 좋은 코스예요. 목적지는 물의정원으로 안내해요."),
            // VisitKorea 104721
            recommendation("heyri", "파주 헤이리 예술마을", "경기 파주시", "경기도 파주시 탄현면 헤이리마을길 70-21",
                    37.7880093, 126.6992980, Category.NEARBY_CAFE, List.of("감성카페", "데이트"),
                    "마을을 걸으며 카페와 전시 공간을 둘러볼 수 있어요."),
            // 테라로사 서종점: https://www.terarosa.com/store/detail/?id=12
            recommendation("seojong-cafe", "양평 서종 카페거리", "경기 양평군", "경기도 양평군 서종면 북한강로 992",
                    37.62484, 127.35491, Category.NEARBY_CAFE, List.of("자연뷰", "힐링"),
                    "북한강을 따라 카페를 둘러보기 좋은 코스예요. 목적지는 테라로사 서종점으로 안내해요."),
            // VisitKorea 87086
            recommendation("dumulmeori", "양평 두물머리", "경기 양평군", "경기도 양평군 양서면 두물머리길 145",
                    37.5336210, 127.3174699, Category.NATURE, List.of("자연", "강변"),
                    "북한강과 남한강이 만나는 강변을 따라 산책할 수 있어요."),
            // VisitKorea 93730: 청평호반로의 쁘띠프랑스
            recommendation("cheongpyeong", "가평 청평호반", "경기 가평군", "경기도 가평군 청평면 호반로 1063",
                    37.7153159, 127.4902950, Category.NATURE, List.of("호수", "자연 풍경"),
                    "청평호반을 따라 달리는 코스예요. 목적지는 호반로의 쁘띠프랑스로 안내해요."),
            // 전국관광안내소표준데이터 15013112: 산정호수 하동주차장 포천관광안내소
            recommendation("sanjeong-lake", "포천 산정호수", "경기 포천시", "경기도 포천시 영북면 산정호수로411번길 5",
                    38.0656120, 127.3156898, Category.NATURE, List.of("호수", "숲길"),
                    "호수 둘레길에 접근할 수 있는 하동주차장 쪽으로 안내해요."),
            // GoCamping 7548
            recommendation("mongsanpo", "태안 몽산포 캠핑장", "충남 태안군", "충청남도 태안군 남면 신장리 353-53",
                    36.6707972, 126.2863962, Category.CAR_CAMPING, List.of("차박", "바다"),
                    "몽산포해변 근처에 있는 오토캠핑장이에요."),
            // GoCamping 2616
            recommendation("jaraseom", "가평 자라섬 캠핑장", "경기 가평군", "경기도 가평군 가평읍 자라섬로 60",
                    37.8208749, 127.5209246, Category.CAR_CAMPING, List.of("차박", "캠핑"),
                    "북한강 자라섬에 있는 캠핑장이에요."),
            // GoCamping 102006
            recommendation("baengnoju", "포천 백로주 캠핑장", "경기 포천시", "경기도 포천시 영중면 호국로 2669",
                    37.9812649, 127.2419948, Category.CAR_CAMPING, List.of("차박", "힐링"),
                    "포천 영중면의 백로주 캠핑앤파크로 안내해요."));
    private static final List<Place> POPULAR_DESTINATIONS = List.of(SEOUL_FOREST);
    private static final List<Place> PLACES =
            Stream.concat(POPULAR_DESTINATIONS.stream(), RECOMMENDED_PLACES.stream()).distinct().toList();

    private static Place recommendation(String slug, String name, String region, String address, double latitude,
            double longitude, Category category, List<String> hashtags, String introduction) {
        String photo = PHOTO_BASE_URL + slug + ".webp";
        return new Place("plc_" + slug.replace('-', '_'), name, region, address, new Coordinate(latitude, longitude),
                photo, category, hashtags, List.of(photo), introduction, null, null, List.of());
    }

    @Override
    public List<Place> findByCategory(Category category) {
        return PLACES.stream().filter(place -> place.category() == category).toList();
    }

    @Override
    public List<Place> findRecommendationsByCategory(Category category) {
        return RECOMMENDED_PLACES.stream().filter(place -> place.category() == category).toList();
    }

    @Override
    public List<Place> findPopularDestinations() {
        return POPULAR_DESTINATIONS;
    }

    @Override
    public Optional<Place> findById(String placeId) {
        return PLACES.stream().filter(place -> placeId.equals(place.id())).findFirst();
    }
}
