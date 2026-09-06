INSERT INTO `commands` (`name`, `access_level`, `class_name`)
VALUES ('petlevel', 200, 'L1PetLevel')
ON DUPLICATE KEY UPDATE
    `access_level` = VALUES(`access_level`),
    `class_name` = VALUES(`class_name`);
