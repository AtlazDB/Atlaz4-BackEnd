package geoRural.service;

import geoRural.entity.ArquivoBruto;
import geoRural.entity.Fonte;
import geoRural.exception.FormatoArquivoNaoAceitoException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.repository.ArquivoBrutoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ArquivoBrutoServiceImpl implements ArquivoBrutoService {

    private static final List<String> EXTENSOES_ACEITAS =
            List.of(".csv", ".zip", ".geojson", ".json", ".shp", ".xlsx", ".txt");

    private final ArquivoBrutoRepository repository;
    private final FonteService fonteService;
    private final ObjectStorageService objectStorageService;

    @Value("${oci.zona-bruta}")
    private String zonaBruta;

    public ArquivoBrutoServiceImpl(ArquivoBrutoRepository repository,
                                   FonteService fonteService,
                                   ObjectStorageService objectStorageService) {
        this.repository = repository;
        this.fonteService = fonteService;
        this.objectStorageService = objectStorageService;
    }

    @Override
    @Transactional
    public ArquivoBruto receber(Long fonteId, MultipartFile arquivo) throws IOException {
        Fonte fonte = fonteService.buscarPorId(fonteId);

        if (arquivo == null || arquivo.isEmpty()) {
            throw new RequisicaoInvalidaException("Nenhum arquivo enviado.");
        }

        String nomeOriginal = arquivo.getOriginalFilename() == null ? "arquivo" : arquivo.getOriginalFilename();
        String extensao = extensao(nomeOriginal);
        if (!EXTENSOES_ACEITAS.contains(extensao)) {
            throw new FormatoArquivoNaoAceitoException(extensao, EXTENSOES_ACEITAS);
        }

        String chave = String.format("%s/%s/%s/%s_%s",
                zonaBruta,
                fonte.getSigla(),
                LocalDate.now(ZoneOffset.UTC),
                UUID.randomUUID().toString().substring(0, 8),
                nomeOriginal);

        MessageDigest sha256 = sha256();
        try (InputStream in = new DigestInputStream(arquivo.getInputStream(), sha256)) {
            objectStorageService.enviar(chave, in, arquivo.getSize(), arquivo.getContentType());
        }

        ArquivoBruto bruto = new ArquivoBruto();
        bruto.setFonte(fonte);
        bruto.setChaveObjeto(chave);
        bruto.setNomeOriginal(nomeOriginal);
        bruto.setTamanhoBytes(arquivo.getSize());
        bruto.setContentType(arquivo.getContentType());
        bruto.setHashSha256(HexFormat.of().formatHex(sha256.digest()));
        bruto.setStatus(ArquivoBruto.STATUS_RECEBIDO);

        return repository.save(bruto);
    }

    @Override
    public List<ArquivoBruto> listarPorFonte(Long fonteId) {
        return repository.findByFonteIdOrderByRecebidoEmDesc(fonteId);
    }

    private static String extensao(String nome) {
        int i = nome.lastIndexOf('.');
        return i < 0 ? "" : nome.substring(i).toLowerCase();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
