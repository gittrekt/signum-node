package brs.http;

import brs.Alias;
import brs.Alias.Offer;
import brs.services.AliasService;
import brs.services.ParameterService;
import com.google.gson.JsonElement;

import javax.servlet.http.HttpServletRequest;

import static brs.http.common.Parameters.*;

public final class GetAlias extends APIServlet.JsonRequestHandler {

  private final ParameterService parameterService;
  private final AliasService aliasService;

  GetAlias(ParameterService parameterService, AliasService aliasService) {
    super(new APITag[] {APITag.ALIASES}, ALIAS_PARAMETER, ALIAS_NAME_PARAMETER, TLD_PARAMETER);
    this.parameterService = parameterService;
    this.aliasService = aliasService;
  }

  @Override
  protected
  JsonElement processRequest(HttpServletRequest req) throws ParameterException {
    final Alias alias = parameterService.getAlias(req);
    final Offer offer = aliasService.getOffer(alias);
    final Alias tld = aliasService.getTLD(alias.getTLD());

    return JSONData.alias(alias, tld, offer, 0);
  }

}
