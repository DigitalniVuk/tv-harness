package tv.harness;

final class CatalogItem {
    final String title;
    final String watchUrl;
    final String posterUrl;
    final String details;
    final String mediaId;
    final String mediaType;
    final String overview;
    final String backdropUrl;
    final String year;
    final String rating;

    CatalogItem(String title, String watchUrl, String posterUrl, String details) {
        this(title, watchUrl, posterUrl, details, "", "", "", null, "", "");
    }

    CatalogItem(String title, String watchUrl, String posterUrl, String details,
                String mediaId, String mediaType, String overview,
                String backdropUrl, String year, String rating) {
        this.title = title;
        this.watchUrl = watchUrl;
        this.posterUrl = posterUrl;
        this.details = details;
        this.mediaId = mediaId == null ? "" : mediaId;
        this.mediaType = mediaType == null ? "" : mediaType;
        this.overview = overview == null ? "" : overview;
        this.backdropUrl = backdropUrl;
        this.year = year == null ? "" : year;
        this.rating = rating == null ? "" : rating;
    }
}
