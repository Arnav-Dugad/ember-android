package io.github.arnavdugad.ember.data.location

import io.github.arnavdugad.ember.data.model.Place
import java.text.Normalizer

/** A city picker row: the place itself plus a region/country line. */
data class CitySuggestion(val place: Place, val detail: String)

/**
 * Popular cities bundled with the app, so the picker works offline and instantly. India-heavy,
 * plus the world's big dating cities. Coordinates are city centres at two decimals, the same
 * precision Ember stores. The Geocoder covers everything else.
 */
object Cities {
    private class City(val name: String, val region: String, val lat: Double, val lng: Double, val aliases: List<String> = emptyList())

    private val all = listOf(
        // India
        City("Mumbai", "Maharashtra, India", 19.08, 72.88, listOf("Bombay")),
        City("Delhi", "India", 28.61, 77.21, listOf("New Delhi", "NCR")),
        City("Bengaluru", "Karnataka, India", 12.97, 77.59, listOf("Bangalore")),
        City("Hyderabad", "Telangana, India", 17.39, 78.49, listOf("Secunderabad")),
        City("Chennai", "Tamil Nadu, India", 13.08, 80.27, listOf("Madras")),
        City("Kolkata", "West Bengal, India", 22.57, 88.36, listOf("Calcutta")),
        City("Pune", "Maharashtra, India", 18.52, 73.86, listOf("Poona")),
        City("Goa", "India", 15.50, 73.83, listOf("Panaji", "Panjim")),
        City("Gurugram", "Haryana, India", 28.46, 77.03, listOf("Gurgaon")),
        City("Noida", "Uttar Pradesh, India", 28.54, 77.39),
        City("Ahmedabad", "Gujarat, India", 23.02, 72.57),
        City("Jaipur", "Rajasthan, India", 26.91, 75.79),
        City("Chandigarh", "India", 30.73, 76.78),
        City("Kochi", "Kerala, India", 9.93, 76.27, listOf("Cochin", "Ernakulam")),
        City("Thane", "Maharashtra, India", 19.22, 72.98),
        City("Navi Mumbai", "Maharashtra, India", 19.03, 73.03),
        City("Lucknow", "Uttar Pradesh, India", 26.85, 80.95),
        City("Indore", "Madhya Pradesh, India", 22.72, 75.86),
        City("Surat", "Gujarat, India", 21.17, 72.83),
        City("Nagpur", "Maharashtra, India", 21.15, 79.09),
        City("Bhopal", "Madhya Pradesh, India", 23.26, 77.41),
        City("Visakhapatnam", "Andhra Pradesh, India", 17.69, 83.22, listOf("Vizag")),
        City("Vijayawada", "Andhra Pradesh, India", 16.51, 80.65),
        City("Patna", "Bihar, India", 25.59, 85.14),
        City("Vadodara", "Gujarat, India", 22.31, 73.18, listOf("Baroda")),
        City("Rajkot", "Gujarat, India", 22.30, 70.80),
        City("Ghaziabad", "Uttar Pradesh, India", 28.67, 77.45),
        City("Faridabad", "Haryana, India", 28.41, 77.32),
        City("Ludhiana", "Punjab, India", 30.90, 75.86),
        City("Amritsar", "Punjab, India", 31.63, 74.87),
        City("Jalandhar", "Punjab, India", 31.33, 75.58),
        City("Agra", "Uttar Pradesh, India", 27.18, 78.01),
        City("Kanpur", "Uttar Pradesh, India", 26.45, 80.33),
        City("Varanasi", "Uttar Pradesh, India", 25.32, 82.97, listOf("Banaras", "Benares")),
        City("Prayagraj", "Uttar Pradesh, India", 25.44, 81.85, listOf("Allahabad")),
        City("Meerut", "Uttar Pradesh, India", 28.98, 77.71),
        City("Nashik", "Maharashtra, India", 20.00, 73.79),
        City("Aurangabad", "Maharashtra, India", 19.88, 75.34, listOf("Chhatrapati Sambhajinagar")),
        City("Kolhapur", "Maharashtra, India", 16.70, 74.24),
        City("Coimbatore", "Tamil Nadu, India", 11.02, 76.96),
        City("Madurai", "Tamil Nadu, India", 9.93, 78.12),
        City("Tiruchirappalli", "Tamil Nadu, India", 10.79, 78.70, listOf("Trichy")),
        City("Puducherry", "India", 11.94, 79.81, listOf("Pondicherry", "Pondy")),
        City("Thiruvananthapuram", "Kerala, India", 8.52, 76.94, listOf("Trivandrum")),
        City("Kozhikode", "Kerala, India", 11.26, 75.78, listOf("Calicut")),
        City("Mysuru", "Karnataka, India", 12.30, 76.64, listOf("Mysore")),
        City("Mangaluru", "Karnataka, India", 12.91, 74.86, listOf("Mangalore")),
        City("Hubballi", "Karnataka, India", 15.36, 75.12, listOf("Hubli")),
        City("Dehradun", "Uttarakhand, India", 30.32, 78.03),
        City("Rishikesh", "Uttarakhand, India", 30.09, 78.27),
        City("Shimla", "Himachal Pradesh, India", 31.10, 77.17),
        City("Manali", "Himachal Pradesh, India", 32.24, 77.19),
        City("Srinagar", "Jammu and Kashmir, India", 34.08, 74.80),
        City("Jammu", "Jammu and Kashmir, India", 32.73, 74.86),
        City("Guwahati", "Assam, India", 26.14, 91.74),
        City("Shillong", "Meghalaya, India", 25.58, 91.89),
        City("Siliguri", "West Bengal, India", 26.73, 88.40),
        City("Bhubaneswar", "Odisha, India", 20.30, 85.82),
        City("Ranchi", "Jharkhand, India", 23.34, 85.31),
        City("Raipur", "Chhattisgarh, India", 21.25, 81.63),
        City("Jodhpur", "Rajasthan, India", 26.24, 73.02),
        City("Udaipur", "Rajasthan, India", 24.59, 73.71),
        City("Kota", "Rajasthan, India", 25.21, 75.86),
        City("Jabalpur", "Madhya Pradesh, India", 23.18, 79.99),
        City("Gwalior", "Madhya Pradesh, India", 26.22, 78.18),
        // South and South-East Asia
        City("Karachi", "Pakistan", 24.86, 67.01),
        City("Lahore", "Pakistan", 31.55, 74.34),
        City("Islamabad", "Pakistan", 33.68, 73.05),
        City("Dhaka", "Bangladesh", 23.81, 90.41),
        City("Kathmandu", "Nepal", 27.72, 85.32),
        City("Colombo", "Sri Lanka", 6.93, 79.86),
        City("Malé", "Maldives", 4.18, 73.51),
        City("Singapore", "Singapore", 1.35, 103.82),
        City("Kuala Lumpur", "Malaysia", 3.14, 101.69, listOf("KL")),
        City("Bangkok", "Thailand", 13.76, 100.50),
        City("Phuket", "Thailand", 7.88, 98.39),
        City("Bali", "Indonesia", -8.65, 115.22, listOf("Denpasar")),
        City("Jakarta", "Indonesia", -6.21, 106.85),
        City("Manila", "Philippines", 14.60, 120.98),
        City("Ho Chi Minh City", "Vietnam", 10.82, 106.63, listOf("Saigon")),
        City("Hanoi", "Vietnam", 21.03, 105.85),
        // East Asia and Oceania
        City("Hong Kong", "China", 22.32, 114.17),
        City("Taipei", "Taiwan", 25.03, 121.57),
        City("Shanghai", "China", 31.23, 121.47),
        City("Beijing", "China", 39.90, 116.41),
        City("Seoul", "South Korea", 37.57, 126.98),
        City("Tokyo", "Japan", 35.68, 139.69),
        City("Osaka", "Japan", 34.69, 135.50),
        City("Sydney", "Australia", -33.87, 151.21),
        City("Melbourne", "Australia", -37.81, 144.96),
        City("Brisbane", "Australia", -27.47, 153.03),
        City("Perth", "Australia", -31.95, 115.86),
        City("Auckland", "New Zealand", -36.85, 174.76),
        // Middle East and Africa
        City("Dubai", "United Arab Emirates", 25.20, 55.27),
        City("Abu Dhabi", "United Arab Emirates", 24.45, 54.38),
        City("Doha", "Qatar", 25.29, 51.53),
        City("Riyadh", "Saudi Arabia", 24.71, 46.68),
        City("Muscat", "Oman", 23.59, 58.41),
        City("Tel Aviv", "Israel", 32.09, 34.78),
        City("Istanbul", "Turkey", 41.01, 28.98),
        City("Cairo", "Egypt", 30.04, 31.24),
        City("Casablanca", "Morocco", 33.57, -7.59),
        City("Marrakesh", "Morocco", 31.63, -8.01, listOf("Marrakech")),
        City("Lagos", "Nigeria", 6.52, 3.38),
        City("Accra", "Ghana", 5.60, -0.19),
        City("Nairobi", "Kenya", -1.29, 36.82),
        City("Johannesburg", "South Africa", -26.20, 28.05, listOf("Joburg")),
        City("Cape Town", "South Africa", -33.92, 18.42),
        // Europe
        City("London", "United Kingdom", 51.51, -0.13),
        City("Manchester", "United Kingdom", 53.48, -2.24),
        City("Edinburgh", "United Kingdom", 55.95, -3.19),
        City("Dublin", "Ireland", 53.35, -6.26),
        City("Paris", "France", 48.86, 2.35),
        City("Lyon", "France", 45.76, 4.84),
        City("Berlin", "Germany", 52.52, 13.40),
        City("Munich", "Germany", 48.14, 11.58, listOf("München")),
        City("Hamburg", "Germany", 53.55, 9.99),
        City("Amsterdam", "Netherlands", 52.37, 4.90),
        City("Brussels", "Belgium", 50.85, 4.35),
        City("Zurich", "Switzerland", 47.38, 8.54, listOf("Zürich")),
        City("Geneva", "Switzerland", 46.20, 6.14),
        City("Vienna", "Austria", 48.21, 16.37, listOf("Wien")),
        City("Prague", "Czechia", 50.08, 14.44),
        City("Warsaw", "Poland", 52.23, 21.01),
        City("Budapest", "Hungary", 47.50, 19.04),
        City("Copenhagen", "Denmark", 55.68, 12.57),
        City("Stockholm", "Sweden", 59.33, 18.07),
        City("Oslo", "Norway", 59.91, 10.75),
        City("Helsinki", "Finland", 60.17, 24.94),
        City("Madrid", "Spain", 40.42, -3.70),
        City("Barcelona", "Spain", 41.39, 2.17),
        City("Lisbon", "Portugal", 38.72, -9.14, listOf("Lisboa")),
        City("Porto", "Portugal", 41.15, -8.61),
        City("Rome", "Italy", 41.90, 12.50, listOf("Roma")),
        City("Milan", "Italy", 45.46, 9.19, listOf("Milano")),
        City("Athens", "Greece", 37.98, 23.73),
        // Americas
        City("New York", "United States", 40.71, -74.01, listOf("NYC", "Manhattan")),
        City("Brooklyn", "New York, United States", 40.68, -73.94),
        City("Boston", "United States", 42.36, -71.06),
        City("Washington, D.C.", "United States", 38.91, -77.04, listOf("Washington DC", "DC")),
        City("Philadelphia", "United States", 39.95, -75.17),
        City("Miami", "United States", 25.76, -80.19),
        City("Atlanta", "United States", 33.75, -84.39),
        City("Chicago", "United States", 41.88, -87.63),
        City("Austin", "United States", 30.27, -97.74),
        City("Houston", "United States", 29.76, -95.37),
        City("Dallas", "United States", 32.78, -96.80),
        City("Denver", "United States", 39.74, -104.99),
        City("Las Vegas", "United States", 36.17, -115.14),
        City("Los Angeles", "United States", 34.05, -118.24, listOf("LA")),
        City("San Francisco", "United States", 37.77, -122.42, listOf("SF")),
        City("San Diego", "United States", 32.72, -117.16),
        City("Seattle", "United States", 47.61, -122.33),
        City("Toronto", "Canada", 43.65, -79.38),
        City("Vancouver", "Canada", 49.28, -123.12),
        City("Montreal", "Canada", 45.50, -73.57, listOf("Montréal")),
        City("Mexico City", "Mexico", 19.43, -99.13, listOf("CDMX")),
        City("Cancún", "Mexico", 21.16, -86.85),
        City("Bogotá", "Colombia", 4.71, -74.07),
        City("Medellín", "Colombia", 6.24, -75.58),
        City("Lima", "Peru", -12.05, -77.04),
        City("São Paulo", "Brazil", -23.55, -46.63),
        City("Rio de Janeiro", "Brazil", -22.91, -43.17, listOf("Rio")),
        City("Buenos Aires", "Argentina", -34.60, -58.38),
        City("Santiago", "Chile", -33.45, -70.67),
    )

    val size: Int get() = all.size

    /** Matching cities, best first: name prefix, then word prefix, then alias, then anywhere. Empty query lists the most popular. */
    fun search(query: String, limit: Int = 30): List<CitySuggestion> {
        val q = fold(query.trim())
        if (q.isEmpty()) return all.take(limit).map { it.suggestion() }
        return all.asSequence()
            .mapNotNull { city -> rank(city, q)?.let { it to city } }
            .sortedBy { it.first }
            .take(limit)
            .map { it.second.suggestion() }
            .toList()
    }

    private fun rank(city: City, q: String): Int? {
        val name = fold(city.name)
        val aliases = city.aliases.map(::fold)
        return when {
            name.startsWith(q) -> 0
            name.split(' ', '-', ',').any { it.startsWith(q) } -> 1
            aliases.any { it.startsWith(q) } -> 2
            name.contains(q) || aliases.any { it.contains(q) } -> 3
            fold(city.region).split(", ").any { it.startsWith(q) } -> 4
            else -> null
        }
    }

    private fun City.suggestion() = CitySuggestion(Place(name, lat, lng), region)

    /** Lowercase without accents, so "sao" finds São Paulo and "zurich" finds Zürich. */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
}
