package geoRural.controller;

import geoRural.entity.ImovelRural;
import geoRural.service.ImovelRuralService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/imoveis")
public class ImovelRuralController {

    private final ImovelRuralService service;

    public ImovelRuralController(ImovelRuralService service) {
        this.service = service;
    }

    @GetMapping
    public List<ImovelRural> listar(
            @RequestParam(required = false) String municipio,
            @RequestParam(required = false) String estado) {

        if (municipio != null) return service.listarPorMunicipio(municipio);
        return service.listarTodos();
    }

    @GetMapping("/{codImovel}")
    public ImovelRural buscarPorCodigo(@PathVariable String codImovel) {
        return service.buscarPorCodImovel(codImovel);
    }
}