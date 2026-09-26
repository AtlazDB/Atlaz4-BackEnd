package geoRural.controller;

import geoRural.dto.CargaResponse;
import geoRural.service.CargaMunicipioService;
import geoRural.service.CargaService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pipeline")
public class PipelineController {

    private final CargaService cargaService;
    private final CargaMunicipioService cargaMunicipioService;

    public PipelineController(CargaService cargaService,
                              CargaMunicipioService cargaMunicipioService) {
        this.cargaService = cargaService;
        this.cargaMunicipioService = cargaMunicipioService;
    }

    // O corpo chega como String e é lido pelo Jackson/JTS no serviço: assim o
    // endpoint aceita application/json e application/geo+json.
    @PostMapping("/carga")
    @ResponseStatus(HttpStatus.CREATED)
    public CargaResponse carregar(
            @RequestParam Long arquivoBrutoId,
            @RequestBody String geoJson) {

        return cargaService.carregar(arquivoBrutoId, geoJson);
    }

    // Mesma forma da carga de imóveis, com a malha municipal do IBGE no corpo.
    @PostMapping("/carga/municipios")
    @ResponseStatus(HttpStatus.CREATED)
    public CargaResponse carregarMunicipios(
            @RequestParam Long arquivoBrutoId,
            @RequestBody String geoJson) {

        return cargaMunicipioService.carregar(arquivoBrutoId, geoJson);
    }
}
