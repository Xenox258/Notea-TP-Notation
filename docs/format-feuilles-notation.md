# Comment créer une feuille de notation compatible avec Notéa

## Règles essentielles

### 1. Format du fichier
- Utiliser exclusivement le format **`.xlsx`** (Excel 2007+).
- Les `.xls` (ancien format) ne sont pas supportés.

### 2. Un seul onglet principal avec du contenu
- La feuille qui contient la grille de notation doit avoir **au moins 5 cellules remplies**.
- Les onglets vides sont ignorés automatiquement.

### 3. La grille doit être en tableau matriciel

Organiser la feuille comme un tableau à double entrée :

```
                         | Niveau 0            | Niveau 1         | Niveau 2       | Niveau 3            | Poids
Critère 1                |                     |                  |                |                     | 2.0
Critère 2                |                     |                  |                |                     | 1.5
Critère 3                |                     |                  |                |                     | 3.0
```

- Une **ligne d'en-tête** qui nomme les 4 niveaux.
- Une **ligne par critère** avec son libellé et sa pondération.
- Les **pondérations** se placent dans une colonne à droite des niveaux (ou dans toute case numérique associée à la ligne du critère).

### 4. Les pondérations sont obligatoires
- Chaque critère doit avoir une **pondération supérieure à 0**.
- Si une ligne n'a pas de pondération, elle sera ignorée.
- Exemples valides : `2.0`, `1.5`, `3`, `0.5`.

### 5. Les 4 niveaux doivent être nommés explicitement
- Le fichier doit contenir **4 niveaux distincts** dans sa ligne d'en-tête.
- Exemples de libellés acceptés : `Très insatisfaisant` / `Insatisfaisant` / `Satisfaisant` / `Très satisfaisant` ou `Insuffisant` / `Fragile` / `Maîtrisé` / `Expert`.

### 6. Export : les cellules sont écrasées automatiquement
Lors de l'export, Notéa écrit des croix (`X`) dans les colonnes des niveaux.  
Les cellules concernées **seront remplacées** : ne pas y mettre de formules ou de contenu important.

### 7. Vérifier avant import
- Toutes les cellules de la grille sont bien dans un **seul onglet** (pas éclatées sur plusieurs feuilles).
- Aucune cellule fusionnée n'empêche la lecture des libellés et pondérations.
- Les noms de critères sont **identiques** dans toute l'app (import, descripteurs éventuels, export).

---

## Check-list avant import dans Notéa

- [ ] Fichier en `.xlsx`
- [ ] Un seul onglet contient toute la grille
- [ ] 4 colonnes de niveaux, avec leurs libellés sur une même ligne
- [ ] Chaque critère a un libellé dans une cellule
- [ ] Chaque critère a une pondération numérique > 0
- [ ] Aucune formule dans les cellules qui recevront les croix d'export
