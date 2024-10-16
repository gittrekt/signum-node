package brs.http;

import static brs.http.common.Parameters.HEX_STRING_PARAMETER;
import static brs.http.common.Parameters.ID_PARAMETER;

import javax.servlet.http.HttpServletRequest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import brs.util.Convert;
import signumj.crypto.SignumCrypto;

final class GetATLong extends APIServlet.JsonRequestHandler {

  static final GetATLong instance = new GetATLong();

  private GetATLong() {
    super(new APITag[] {APITag.AT}, HEX_STRING_PARAMETER, ID_PARAMETER);
  }

  @Override
  protected
  JsonElement processRequest(HttpServletRequest req) {
    String id = Convert.emptyToNull(req.getParameter(ID_PARAMETER));
    if(id != null){
      JsonObject json = new JsonObject();
      json.addProperty("long2hex", Convert.toHexString(SignumCrypto.getInstance().longToBytesLE(Convert.parseUnsignedLong(id))));
      return json;
    }

    return JSONData.hex2long(ParameterParser.getATLong(req));
  }

}
