package geoRural.controller;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import geoRural.service.ObjectStorageService;

@RestController
public class ObjectStorageController {

    private final ObjectStorageService objectStorageService;

    public ObjectStorageController(ObjectStorageService objectStorageService) {
        this.objectStorageService = objectStorageService;
    }

    @PostMapping("/arquivos")
    public String enviarArquivo(@RequestParam String caminho) throws IOException {

        objectStorageService.enviarArquivo(Path.of(caminho));

        return "Arquivo enviado com sucesso!";
    }
}