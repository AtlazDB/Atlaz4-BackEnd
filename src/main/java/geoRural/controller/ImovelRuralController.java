package geoRural.controller;

import geoRural.dto.ImovelRuralResponse;
import geoRural.entity.ImovelRural;
import geoRural.service.GeoJsonService;
import geoRural.service.ImovelRuralService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/imoveis")
public class ImovelRuralController {

    private final ImovelRuralService service;
    private final GeoJsonService geoJsonService;

    public ImovelRuralController(
            ImovelRuralService service,
            GeoJsonService geoJsonService) {
        this.service = service;
        this.geoJsonService = geoJsonService;
    }

    @GetMapping
    public List<ImovelRuralResponse> listar(
            @RequestParam(required = false) String municipio,
            @RequestParam(required = false) String estado) {

        List<ImovelRural> imoveis;

        if (municipio != null) {
            imoveis = service.listarPorMunicipio(municipio);
        } else {
            imoveis = service.listarTodos();
        }

        return imoveis.stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{codImovel}")
    public ImovelRuralResponse buscarPorCodigo(
            @PathVariable String codImovel) {

        ImovelRural imovel = service.buscarPorCodImovel(codImovel);

        return toResponse(imovel);
    }

    private ImovelRuralResponse toResponse(ImovelRural imovel) {
        return new ImovelRuralResponse(
                imovel.getId(),
                imovel.getCodImovel(),
                imovel.getMunicipio(),
                imovel.getEstado(),
                imovel.getAreaHa(),
                geoJsonService.converter(imovel.getGeometria()),
                imovel.getDatasetVersaoId()
        );
    }
}