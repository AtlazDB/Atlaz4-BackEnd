--------------------------------------------------------------------------------
-- GeoRural DataHub — Conserto do anel do polígono de TESTE-001
--------------------------------------------------------------------------------
-- Depende de.: V3 (insere TESTE-001), V4 (tolerância 0.05 e índice remontado)
-- Escopo.....: conserto de dado. O polígono da V3 nasceu com o anel EXTERNO
--              em sentido HORÁRIO. Em SRID geodético o sentido do anel decide
--              o que é "dentro": anel horário = todo o planeta menos o
--              retângulo, e SDO_AREA / SDO_RELATE passam a mentir.
--
-- Por que aqui, e não editando a V3: a V3 já está na dev e já rodou no banco
-- de todo mundo. Editar o arquivo dá "Migration checksum mismatch for
-- migration version 3" para quem já aplicou — e nem conserta o dado, porque
-- migration aplicada não roda de novo.
--
-- Regra: anel externo sempre SW -> SE -> NE -> NW -> fecha.
--------------------------------------------------------------------------------

UPDATE imovel_rural
   SET geometria = SDO_GEOMETRY(
         2003,
         4326,
         NULL,
         SDO_ELEM_INFO_ARRAY(1, 1003, 1),
         SDO_ORDINATE_ARRAY(
           -51.1700, -23.3100,   -- SW
           -51.1600, -23.3100,   -- SE
           -51.1600, -23.3000,   -- NE
           -51.1700, -23.3000,   -- NW
           -51.1700, -23.3100    -- fecha
         )
       )
 WHERE cod_imovel = 'TESTE-001';

COMMIT;

--------------------------------------------------------------------------------
-- PROVA — a migration falha se o anel continuar invertido
--------------------------------------------------------------------------------
-- Um SELECT solto erraria calado. Este bloco derruba a subida da aplicação
-- se a geometria não validar.
--------------------------------------------------------------------------------

DECLARE
  v_resultado VARCHAR2(200);
BEGIN
  SELECT SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, 0.05)
    INTO v_resultado
    FROM imovel_rural
   WHERE cod_imovel = 'TESTE-001';

  IF v_resultado <> 'TRUE' THEN
    RAISE_APPLICATION_ERROR(-20001,
      'Anel de TESTE-001 invalido: ' || v_resultado);
  END IF;
END;
/
