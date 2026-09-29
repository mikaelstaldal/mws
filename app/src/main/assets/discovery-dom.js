(function () {
    'use strict';
    if (document.readyState === 'loading') return 'mws-dom-pending';
    const encoder = new TextEncoder();
    const bounded = value => encoder.encode(value).length <= 8192 ? value : null;
    const policy = value => ['no-referrer', 'no-referrer-when-downgrade', 'origin',
        'origin-when-cross-origin', 'same-origin', 'strict-origin',
        'strict-origin-when-cross-origin', 'unsafe-url'].includes(value) ? value : null;
    // WebView does not expose the response-header policy to this script.
    // With no DOM override, suppress referrers rather than risk weakening that policy.
    let documentPolicy = 'no-referrer';
    for (const meta of document.querySelectorAll('meta[name]')) {
        if (meta.name.toLowerCase() === 'referrer') {
            documentPolicy = policy(meta.content.toLowerCase()) || documentPolicy;
        }
    }
    const manifests = [], icons = [];
    for (const link of document.querySelectorAll('link[rel]')) {
        const rel = link.rel.toLowerCase().split(/\s+/);
        const manifest = rel.includes('manifest');
        const icon = rel.includes('icon') || rel.includes('apple-touch-icon') || rel.includes('apple-touch-icon-precomposed');
        if ((!manifest || manifests.length >= 16) && (!icon || icons.length >= 128)) continue;
        const media = bounded(link.media || '');
        let matches = false;
        try { matches = !link.media || window.matchMedia(link.media).matches; } catch (_) {}
        const item = {
            url: bounded(link.href), media, mediaMatches: matches,
            type: bounded(link.type || ''),
            crossorigin: bounded(link.getAttribute('crossorigin') || ''),
            referrerPolicy: policy(link.referrerPolicy) || documentPolicy,
            sizes: bounded(link.getAttribute('sizes') || '')
        };
        item.usable = Object.values(item).every(value => value !== null);
        if (manifest && manifests.length < 16) manifests.push(item);
        if (icon && icons.length < 128) icons.push(item);
    }
    // Only the first refresh declaration counts, as in a browser. A zero delay marks a redirect page.
    let refresh = null;
    for (const meta of document.querySelectorAll('meta[http-equiv]')) {
        if (meta.httpEquiv.toLowerCase() !== 'refresh') continue;
        const match = /^\s*0+(?:\.0*)?\s*[;,]\s*(?:url\s*=\s*)?(['"]?)(.*)$/i.exec(meta.content);
        if (match) {
            let target = match[2].trim();
            if (match[1] && target.indexOf(match[1]) >= 0) target = target.slice(0, target.indexOf(match[1]));
            try { refresh = target ? bounded(new URL(target, document.baseURI).href) : null; } catch (_) {}
        }
        break;
    }
    const result = JSON.stringify({title: bounded(document.title), manifests, icons, refresh});
    return encoder.encode(result).length <= 1048576 ? result : null;
})()
