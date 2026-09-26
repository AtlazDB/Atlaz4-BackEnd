package geoRural.controller;

import geoRural.dto.MunicipioResponse;
import geoRural.dto.MunicipioResumoResponse;
import geoRural.entity.Municipio;
import geoRural.service.GeoJsonService;
import geoRural.service.MunicipioService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/municipios")
public class MunicipioController {

    private final MunicipioService service;
    private final GeoJsonService geoJsonService;

    public MunicipioController(
            MunicipioService service,
            GeoJsonService geoJsonService) {
        this.service = service;
        this.geoJsonService = geoJsonService;
    }

    @GetMapping
    public List<MunicipioResumoResponse> listar(
            @RequestParam(required = false) String estado) {

        return service.listar(estado).stream()
                .map(MunicipioResumoResponse::de)
                .toList();
    }

    @GetMapping("/{codIbge}")
    public MunicipioResponse buscarPorCodigo(
            @PathVariable String codIbge) {

        Municipio municipio = service.buscarPorCodIbge(codIbge);

        return MunicipioResponse.de(
                municipio,
                geoJsonService.converter(municipio.getGeometria())
        );
    }
}
