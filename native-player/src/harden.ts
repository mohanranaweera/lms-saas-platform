/**
 * Freezes `Object.prototype` in the player UI's own realm, before any other
 * module runs - the prototype-pollution hardening Tauri's `freezePrototype`
 * gave us. Tauri's option also injects the freeze into every iframe, and the
 * YouTube embed breaks there (assigning `toString` on its objects throws once
 * the inherited property is frozen), so the option is off and the freeze
 * lives here. Third-party frames are separate realms and can't reach this one.
 *
 * Must stay the first import of `main.ts`.
 */
Object.freeze(Object.prototype);

export {};
