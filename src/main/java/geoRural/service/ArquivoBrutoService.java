package geoRural.service;

import geoRural.entity.ArquivoBruto;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface ArquivoBrutoService {

    ArquivoBruto receber(Long fonteId, MultipartFile arquivo) throws IOException;

    List<ArquivoBruto> listarPorFonte(Long fonteId);

}
