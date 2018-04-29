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
    @GET("status")
    Call<ResponseBody> status(
        @Header("api-secret") String secretHash
    );

    @GET("{collection}")
    Call<ResponseBody> get(
        @Header("api-secret") String secretHash,
        @Path("collection") String collection
    );

    @GET("{collection}")
    Call<ResponseBody> get(
        @Header("api-secret") String secretHash,
        @Path("collection") String collection,
        @Query("count") Integer count,
        @Query("fromDate") String fromDate,
        @Query("fromMs") Long fromMs
    );

    @POST("delta")
    Call<ResponseBody> delta(
        @Header("api-secret") String secretHash,
        @Query("count") Integer count,
        @Body RequestBody body
    );

    @POST("{collection}")
    Call<ResponseBody> post(
        @Header("api-secret") String secretHash,
        @Path("collection") String collection,
        @Body RequestBody body
    );

    @PUT("{collection}")
    Call<ResponseBody> put(
        @Header("api-secret") String secretHash,
        @Path("collection") String collection,
        @Body RequestBody body
    );

    @DELETE("{collection}/{id}")
    Call<ResponseBody> delete(
        @Header("api-secret") String secretHash,
        @Path("collection") String collection,
        @Path("id") String id
    );
}
