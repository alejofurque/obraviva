package com.obraviva.platform.sharing;

import com.obraviva.platform.sharing.MyPropertyDtos.MyPropertyResponse;
import com.obraviva.platform.sharing.MyPropertyDtos.MyUnitSummary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
@RequestMapping("/api")
public class MyPropertyController {

    private final MyPropertyService myPropertyService;

    public MyPropertyController(MyPropertyService myPropertyService) {
        this.myPropertyService = myPropertyService;
    }

    @GetMapping("/my-units")
    public List<MyUnitSummary> myUnits(@AuthenticationPrincipal Jwt jwt) {
        return myPropertyService.myUnits(userId(jwt));
    }

    @GetMapping("/my-units/{unitId}")
    public MyPropertyResponse property(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID unitId) {
        return myPropertyService.property(userId(jwt), unitId);
    }
}