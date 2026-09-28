package geoRural.service;

import geoRural.entity.ArquivoBruto;
import geoRural.entity.Fonte;
import geoRural.exception.ArquivoNaoEncontradoException;
import geoRural.exception.ExclusaoNaoPermitidaException;
import geoRural.exception.FormatoArquivoNaoAceitoException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.repository.ArquivoBrutoRepository;
import geoRural.repository.DatasetVersaoRepository;
import geoRural.repository.ExecucaoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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

    private static final Logger log = LoggerFactory.getLogger(ArquivoBrutoServiceImpl.class);

    private static final List<String> EXTENSOES_ACEITAS =
            List.of(".csv", ".zip", ".geojson", ".json", ".shp", ".xlsx", ".txt");

    private final ArquivoBrutoRepository repository;
    private final FonteService fonteService;
    private final ObjectStorageService objectStorageService;
    private final ExecucaoRepository execucaoRepository;
    private final DatasetVersaoRepository datasetVersaoRepository;
    private final TransactionTemplate transacao;

    @Value("${oci.zona-bruta}")
    private String zonaBruta;

    public ArquivoBrutoServiceImpl(ArquivoBrutoRepository repository,
                                   FonteService fonteService,
                                   ObjectStorageService objectStorageService,
                                   ExecucaoRepository execucaoRepository,
                                   DatasetVersaoRepository datasetVersaoRepository,
                                   TransactionTemplate transacao) {
        this.repository = repository;
        this.fonteService = fonteService;
        this.objectStorageService = objectStorageService;
        this.execucaoRepository = execucaoRepository;
        this.datasetVersaoRepository = datasetVersaoRepository;
        this.transacao = transacao;
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

    /**
     * Exceção à imutabilidade da zona bruta: um arquivo REJEITADO que nunca gerou versão de dados
     * não é origem de nenhum número exibido, então apagá-lo não quebra a rastreabilidade.
     *
     * O banco vai primeiro, numa transação (quarentena → execuções → arquivo); o objeto no bucket
     * só depois do commit. Se o banco falhar, nada muda. Se só o bucket falhar, sobra um objeto
     * órfão em bruta/ — registrado no log, mas o arquivo já não aparece para ninguém.
     */
    @Override
    public void excluirRejeitado(Long arquivoId) {
        String chave = transacao.execute(status -> {
            ArquivoBruto arquivo = repository.findById(arquivoId)
                    .orElseThrow(() -> new ArquivoNaoEncontradoException(arquivoId));

            if (!ArquivoBruto.STATUS_REJEITADO.equals(arquivo.getStatus())) {
                throw new ExclusaoNaoPermitidaException(
                        "Só arquivos rejeitados podem ser excluídos. Este está " + arquivo.getStatus() + ".");
            }
            // Um arquivo que já foi PROCESSADO e depois falhou num reprocessamento fica REJEITADO,
            // mas a versão da primeira vez continua valendo e há imóveis apontando para ela.
            if (datasetVersaoRepository.existsByExecucao_ArquivoBruto_Id(arquivoId)) {
                throw new ExclusaoNaoPermitidaException(
                        "Este arquivo já gerou uma versão de dados em uso e não pode ser excluído.");
            }

            execucaoRepository.apagarQuarentenaDoArquivo(arquivoId);
            execucaoRepository.apagarDoArquivo(arquivoId);
            if (repository.apagarSeRejeitado(arquivoId) == 0) {
                throw new ExclusaoNaoPermitidaException(
                        "O arquivo mudou de status durante a exclusão. Atualize a tela e tente de novo.");
            }
            return arquivo.getChaveObjeto();
        });

        try {
            objectStorageService.apagar(chave);
        } catch (RuntimeException e) {
            log.warn("Arquivo {} excluído do catálogo, mas o objeto {} ficou no bucket", arquivoId, chave, e);
        }
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
