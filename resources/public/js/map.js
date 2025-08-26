// Border Blur - Map Integration for Location Reveals
// This script handles the interactive map showing Tel Aviv boundaries and image locations

document.addEventListener('DOMContentLoaded', function() {
    const mapElement = document.getElementById('reveal-map');
    
    if (mapElement) {
        initRevealMap(mapElement);
    }
});

function initRevealMap(mapElement) {
    // Get data from HTML attributes
    const coords = mapElement.dataset.coords.split(',').map(parseFloat);
    const isInTelAviv = mapElement.dataset.telAviv === 'true';
    const locationName = mapElement.dataset.location;
    
    // Tel Aviv center coordinates
    const telAvivCenter = [32.0853, 34.7818];
    
    // Initialize map centered on Tel Aviv
    const map = L.map('reveal-map').setView(telAvivCenter, 11);
    
    // Add OpenStreetMap tiles
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 18,
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(map);
    
    // Tel Aviv boundary - synchronized with GIS system
    // CRITICAL: These coordinates must match src/border_blur/gis/cities.clj
    const telAvivBoundary = [
        [32.1200, 34.7500], // Northwest
        [32.1200, 34.8200], // Northeast (fixed from 32.10 to 32.12)
        [32.0500, 34.8200], // Southeast (fixed from 34.81 to 34.82)
        [32.0500, 34.7500], // Southwest (fixed from 32.04, 34.74 to 32.05, 34.75)
        [32.1200, 34.7500]  // Close polygon
    ];
    
    // Add Tel Aviv boundary overlay
    const boundaryLayer = L.polygon(telAvivBoundary, {
        color: '#2196F3',
        weight: 3,
        fillOpacity: 0.1,
        fillColor: '#2196F3'
    }).addTo(map);
    
    // Create marker with appropriate color
    const markerColor = isInTelAviv ? '#4CAF50' : '#FF5722'; // Green for Tel Aviv, Red for outside
    const markerIcon = L.divIcon({
        html: `<div style="
            background-color: ${markerColor}; 
            border: 2px solid white; 
            border-radius: 50%; 
            width: 20px; 
            height: 20px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.3);
            display: flex;
            align-items: center;
            justify-content: center;
            color: white;
            font-weight: bold;
            font-size: 12px;
        ">📍</div>`,
        iconSize: [20, 20],
        iconAnchor: [10, 10]
    });
    
    // Add location marker
    const marker = L.marker([coords[1], coords[0]], { icon: markerIcon }).addTo(map);
    
    // Add popup with location information
    const popupContent = `
        <div style="text-align: center;">
            <strong>${locationName}</strong><br>
            <span style="color: ${markerColor};">
                ${isInTelAviv ? '✓ In Tel Aviv' : '✗ Outside Tel Aviv'}
            </span>
        </div>
    `;
    marker.bindPopup(popupContent);
    
    // Animate to the location after a brief delay
    setTimeout(() => {
        map.flyTo([coords[1], coords[0]], 14, {
            duration: 1.5,
            easeLinearity: 0.5
        });
        
        // Open popup after animation
        setTimeout(() => {
            marker.openPopup();
        }, 1600);
    }, 500);
    
    // Add legend styling
    const legend = document.querySelector('.map-legend');
    if (legend) {
        legend.style.marginTop = '10px';
        legend.style.fontSize = '14px';
        
        const boundarySpan = legend.querySelector('.tel-aviv-boundary');
        if (boundarySpan) {
            boundarySpan.style.color = '#2196F3';
            boundarySpan.style.fontWeight = 'bold';
        }
        
        const markerSpan = legend.querySelector('.location-marker');
        if (markerSpan) {
            markerSpan.style.color = markerColor;
            markerSpan.style.fontWeight = 'bold';
        }
    }
}

// Export for potential future use
window.BorderBlurMap = {
    initRevealMap: initRevealMap
};