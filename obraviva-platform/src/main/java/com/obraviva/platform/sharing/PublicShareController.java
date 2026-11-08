package com.obraviva.platform.sharing;

import com.obraviva.platform.sharing.PublicViewDtos.PublicUnitView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vista pública de una unidad. Único punto del sistema que responde sin sesión.
 *
 * <p>El token es la credencial y va en la ruta. No se lee ningún encabezado de
 * autenticación ni ningún principal: un enlace público que además mirara la sesión
 * sería una vía para que un usuario autenticado obtenga, a través de él, algo distinto
 * de lo que el enlace concede.
 */
@RestController
@RequestMapping("/api/public")
public class PublicShareController {

    private final PublicViewService publicViewService;

    public PublicShareController(PublicViewService publicViewService) {
        this.publicViewService = publicViewService;
    }

    @GetMapping("/share/{token}")
    public PublicUnitView view(@PathVariable String token) {
        PublicUnitView view = publicViewService.view(token);
        // Se registra después de resolver: un token que no sirve no cuenta como visita.
        publicViewService.registerAccess(token);
        return view;
    }
}