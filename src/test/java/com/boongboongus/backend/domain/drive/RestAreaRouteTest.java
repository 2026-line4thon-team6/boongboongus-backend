package com.boongboongus.backend.domain.drive;

import static org.assertj.core.api.Assertions.assertThat;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.LiveRoute;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Road;
import com.boongboongus.backend.domain.drive.RestAreaCatalog.Access;
import com.boongboongus.backend.domain.drive.RestAreaCatalog.Area;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestAreaRouteTest {
    @Test
    void rejectsOppositeParallelAndCrossingSegmentsAtAnEntrance() {
        var entrance = new Coordinate(37.02, 127);
        var area = new Area("rest_fixture", "휴게소", "테스트선", "상행", entrance, null, null);
        var access = new Access(area, entrance, 0, 60);
        var north = new Coordinate(37.03, 127);
        var south = new Coordinate(37.01, 127);
        var west = new Coordinate(37.02, 126.99);
        var east = new Coordinate(37.02, 127.01);
        var opposite = new Road("테스트고속도로", List.of(north, south));
        var parallel = new Road("다른고속도로", List.of(south, north));
        var crossing = new Road("테스트고속도로", List.of(west, east));

        var unrelatedRoute = new RestAreaRoute(new LiveRoute("route", "", List.of(opposite, parallel, crossing)));
        assertThat(unrelatedRoute.matches(List.of(access))).isEmpty();

        var sameDirection = new Road("테스트고속도로", List.of(south, north));
        var correctRoute = new RestAreaRoute(new LiveRoute("route", "", List.of(sameDirection)));
        assertThat(correctRoute.matches(List.of(access))).hasSize(1);
    }
}
