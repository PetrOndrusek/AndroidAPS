package info.nightscout.androidaps.plugins.NSClientInternal.services.REST;

import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Created by PetrOndrusek on 29.03.2018.
 */

public interface NightscoutService {
    @GET("status.json")
    Call<ResponseBody> getStatus(@Header("api-secret") String secret);
}
