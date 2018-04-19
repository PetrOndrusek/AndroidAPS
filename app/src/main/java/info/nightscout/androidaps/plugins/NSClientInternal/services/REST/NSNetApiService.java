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

public interface NSNetApiService {
    @GET("{collection}")
    Call<ResponseBody> get(@Path("collection") String collection);

    @GET("delta")
    Call<ResponseBody> delta(
        @Query("collections") String collections,
        @Query("count") Integer count,
        @Query("includeDeleted") Boolean includeDeleted,
        @Query("fromModified") String fromModified
    );

    @POST("{collection}")
    Call<ResponseBody> post(@Header("api-secret") String secretHash,
                            @Path("collection") String collection,
                            @Body RequestBody body);

    @PUT("{collection}")
    Call<ResponseBody> put(@Header("api-secret") String secretHash,
                           @Path("collection") String collection,
                           @Body RequestBody body);

    @DELETE("{collection}/{id}")
    Call<ResponseBody> delete(@Header("api-secret") String secretHash,
                              @Path("collection") String collection,
                              @Path("id") String id);
}
