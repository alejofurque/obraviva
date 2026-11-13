package com.obraviva.platform.overview;

import com.obraviva.platform.overview.FieldWorkService.AssignedHouse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
public class FieldWorkController {

    private final FieldWorkService service;

    public FieldWorkController(FieldWorkService service) {
        this.service = service;
    }

    @GetMapping("/api/my-work")
    public List<AssignedHouse> myHouses(@AuthenticationPrincipal Jwt jwt) {
        return service.myHouses(userId(jwt));
    }
}
