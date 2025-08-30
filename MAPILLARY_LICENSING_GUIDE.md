# Mapillary Images: Licensing and Attribution Guide

## License Overview

**✅ Public Website Usage**: Mapillary images can be used on public websites  
**License**: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)  
**Commercial Use**: Allowed for research, studies, and service provision

## Required Attribution

### For Individual Images
When displaying Mapillary images on your website, you must:

1. **Display Mapillary Logo**: Include the official Mapillary logo
2. **Link to Mapillary**: Provide a clickable link to https://www.mapillary.com
3. **Image Attribution**: Include creator information when available

### HTML Attribution Example
```html
<div class="image-attribution">
  <img src="mapillary-image.jpg" alt="Street view" />
  <p>Image via 
    <a href="https://www.mapillary.com" target="_blank">
      <img src="mapillary-logo.png" alt="Mapillary" style="height: 20px;" />
    </a>
    | Creator: [username if available] | 
    <a href="https://creativecommons.org/licenses/by-sa/4.0/" target="_blank">CC BY-SA 4.0</a>
  </p>
</div>
```

### Text Attribution Format
```
Image by [Creator Name] via Mapillary (https://www.mapillary.com)
Licensed under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/)
```

## CC BY-SA 4.0 Requirements

### Must Include:
- ✅ Creator attribution (when available)
- ✅ Link to original license
- ✅ Notice if images were modified
- ✅ Mapillary branding/link (per Mapillary Terms)

### ShareAlike Provision:
If you modify the images (crop, filter, enhance), your derivative work must also be licensed under CC BY-SA 4.0.

## Implementation for Your NYC Project

### Recommended Attribution Block
```html
<div class="attribution-block">
  <p><strong>Image Data:</strong> Street view imagery collected via 
    <a href="https://www.mapillary.com" target="_blank">
      Mapillary
    </a>
  </p>
  <p><strong>License:</strong> 
    <a href="https://creativecommons.org/licenses/by-sa/4.0/" target="_blank">
      Creative Commons Attribution-ShareAlike 4.0 International
    </a>
  </p>
  <p><strong>Usage:</strong> Images may be cropped or resized for display purposes</p>
</div>
```

### Footer Attribution (Minimal)
```html
<footer>
  <p>Street view data via 
    <a href="https://www.mapillary.com">Mapillary</a> | 
    <a href="https://creativecommons.org/licenses/by-sa/4.0/">CC BY-SA 4.0</a>
  </p>
</footer>
```

## What You CAN Do

✅ **Display on public websites**  
✅ **Use for research and educational purposes**  
✅ **Crop or resize images for display**  
✅ **Use commercially (with proper attribution)**  
✅ **Create derivative works** (must share under same license)

## What You CANNOT Do

❌ **Remove attribution requirements**  
❌ **Re-identify blurred faces or license plates**  
❌ **Use for real-time navigation**  
❌ **Apply additional restrictions beyond CC BY-SA**  
❌ **Suggest Mapillary endorses your project**

## Code Implementation

### Add Attribution to Image Data
Update your collection code to include attribution metadata:

```clojure
(defn enhance-image-metadata [image]
  (assoc image
    :attribution {:source "Mapillary"
                  :license "CC BY-SA 4.0"
                  :license-url "https://creativecommons.org/licenses/by-sa/4.0/"
                  :creator (:creator image)
                  :mapillary-url "https://www.mapillary.com"}))
```

### Frontend Display Component
```javascript
function ImageWithAttribution({image}) {
  return (
    <div className="image-container">
      <img src={image.url} alt="NYC Street View" />
      <div className="attribution">
        <span>Via <a href="https://www.mapillary.com">Mapillary</a></span>
        {image.creator && <span> | By {image.creator}</span>}
        <span> | <a href="https://creativecommons.org/licenses/by-sa/4.0/">CC BY-SA 4.0</a></span>
      </div>
    </div>
  );
}
```

## Legal Compliance Summary

**Status**: ✅ **SAFE FOR PUBLIC USE**

1. **Attribution**: Required and documented above
2. **Commercial Use**: Explicitly allowed for research/studies
3. **Modification**: Allowed (cropping, resizing) with ShareAlike licensing
4. **Distribution**: Permitted on public websites
5. **Research Use**: Fully compliant for academic/research purposes

## Recommended Actions

1. **Add Attribution**: Implement attribution block on all pages displaying Mapillary images
2. **Include License**: Link to CC BY-SA 4.0 license text  
3. **Document Changes**: Note any modifications made to images
4. **Logo Display**: Include Mapillary branding as required
5. **Update Data**: Enhance collected image metadata with attribution fields

**Note**: This analysis is based on current Mapillary Terms (as of 2024) and CC BY-SA 4.0 license. Always verify current terms before public deployment.